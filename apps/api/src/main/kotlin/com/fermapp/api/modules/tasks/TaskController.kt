package com.fermapp.api.modules.tasks

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.model.Role
import com.fermapp.api.domain.model.TaskPriority
import com.fermapp.api.domain.model.TaskStatus
import com.fermapp.api.domain.model.TaskType
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.TaskWithRefs
import com.fermapp.api.modules.notifications.NotificationService
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput
import com.fermapp.api.persistence.entity.Task
import com.fermapp.api.persistence.repository.TaskRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.criteria.Predicate
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.EnumMap
import java.util.UUID
import org.openapitools.jackson.nullable.JsonNullable
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.ResponseEntity
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Tasks (tasks.routes.ts). Operators can only change the status of their own tasks. */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "To Do")
class TaskController(
    private val tasks: TaskRepository,
    private val access: AccessGuard,
    private val realtime: RealtimeHub,
    private val notifications: NotificationService,
    private val tx: TransactionTemplate,
) {

    @JvmRecord
    data class TaskTypeOption(val code: TaskType, val label: String)

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("type", "title", "description", "priority", "dueDate", "parcelId", "assigneeId")
    class TaskCreate(
        @field:NotNull val type: TaskType?,
        title: String?,
        description: String?,
        priority: TaskPriority?,
        @field:NotNull val dueDate: Instant?,
        val parcelId: UUID?,
        val assigneeId: UUID?,
    ) {
        @field:Size(min = 1, max = 160)
        val title: String? = Text.trim(title)

        @field:Size(max = 2000)
        val description: String? = Text.trim(description)

        val priority: TaskPriority? = priority ?: TaskPriority.MEDIUM
    }

    /** A partial update: absent fields stay as they are, null clears the nullable ones. */
    class TaskUpdate(
        type: JsonNullable<TaskType>?,
        title: JsonNullable<String>?,
        description: JsonNullable<String>?,
        priority: JsonNullable<TaskPriority>?,
        dueDate: JsonNullable<Instant>?,
        parcelId: JsonNullable<UUID>?,
        assigneeId: JsonNullable<UUID>?,
    ) {
        val type: JsonNullable<@NotNull TaskType> = type ?: JsonNullable.undefined()
        val title: JsonNullable<@NotNull @Size(min = 1, max = 160) String> =
            if (title == null || !title.isPresent) JsonNullable.undefined() else JsonNullable.of(Text.trim(title.get()))
        val description: JsonNullable<@Size(max = 2000) String> =
            if (description == null || !description.isPresent) JsonNullable.undefined() else JsonNullable.of(Text.trim(description.get()))
        val priority: JsonNullable<@NotNull TaskPriority> = priority ?: JsonNullable.undefined()
        val dueDate: JsonNullable<@NotNull Instant> = dueDate ?: JsonNullable.undefined()
        val parcelId: JsonNullable<UUID> = parcelId ?: JsonNullable.undefined()
        val assigneeId: JsonNullable<UUID> = assigneeId ?: JsonNullable.undefined()
    }

    class StatusRequest(@field:NotNull val status: TaskStatus?)

    private fun requireTask(farmId: UUID, id: UUID): Task = tasks.findByIdAndFarmId(id, farmId).orElseThrow { Errors.notFound("Task") }

    /** Reads the task with its parcel and assignee in a fresh persistence context. */
    private fun load(id: UUID): TaskWithRefs =
        tx.execute { TaskWithRefs.of(tasks.findById(id).orElseThrow { Errors.recordNotFound() }) }!!

    @GetMapping("/types")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Predefined tasks")
    fun types(): ResponseEntity<ApiResponse<List<TaskTypeOption>>> =
        ApiResponse.ok(TASK_LABELS.map { (code, label) -> TaskTypeOption(code, label) })

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List tasks")
    fun list(
        auth: AuthContext,
        @RequestParam(required = false) status: TaskStatus?,
        @RequestParam(required = false) parcelId: UUID?,
        @RequestParam(defaultValue = "false") @Pattern(regexp = "true|false") mine: String,
    ): ResponseEntity<ApiResponse<List<TaskWithRefs>>> {
        val spec = Specification<Task> { root, _, cb ->
            val predicates = mutableListOf<Predicate>(cb.equal(root.get<UUID>("farmId"), auth.farmId()))
            if (status != null) {
                predicates += cb.equal(root.get<TaskStatus>("status"), status)
            }
            if (parcelId != null) {
                predicates += cb.equal(root.get<UUID>("parcelId"), parcelId)
            }
            if (mine == "true") {
                predicates += cb.equal(root.get<UUID>("assigneeId"), auth.userId())
            }
            cb.and(*predicates.toTypedArray())
        }
        val page = PageRequest.of(0, 500, Sort.by(Sort.Order.asc("status"), Sort.Order.asc("dueDate")))
        return ApiResponse.ok(tx.execute { tasks.findAll(spec, page).content.map { TaskWithRefs.of(it) } }!!)
    }

    @PostMapping
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Create a task")
    fun create(auth: AuthContext, @Valid @RequestBody body: TaskCreate): ResponseEntity<ApiResponse<TaskWithRefs>> {
        if (body.parcelId != null) {
            access.requireParcel(auth.farmId(), body.parcelId)
        }
        if (body.assigneeId != null) {
            access.requireMember(auth.farmId(), body.assigneeId)
        }
        val task = Task()
        task.farmId = auth.farmId()
        task.type = body.type
        task.title = body.title ?: TASK_LABELS[body.type]
        task.description = body.description
        task.priority = body.priority
        task.dueDate = body.dueDate
        task.parcelId = body.parcelId
        task.assigneeId = body.assigneeId
        tasks.saveAndFlush(task)
        val created = load(task.id)
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.TASK_UPDATED, created)
        val assignee = task.assigneeId
        if (assignee != null && assignee != auth.userId()) {
            val where = created.parcel?.let { " on parcel " + it.name } ?: ""
            notifications.notify(
                NotificationInput(
                    auth.farmId(), listOf(assignee), null, "New task", created.task.title + where + ".",
                    mapOf("type" to "task", "id" to task.id.toString()),
                ),
            )
        }
        return ApiResponse.created(created)
    }

    @PatchMapping("/{id}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Update a task")
    fun update(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: TaskUpdate): ResponseEntity<ApiResponse<TaskWithRefs>> {
        val current = requireTask(auth.farmId(), id)
        if (body.parcelId.isPresent && body.parcelId.get() != null) {
            access.requireParcel(auth.farmId(), body.parcelId.get())
        }
        if (body.assigneeId.isPresent && body.assigneeId.get() != null) {
            access.requireMember(auth.farmId(), body.assigneeId.get())
        }
        val dueChanged = body.dueDate.isPresent && body.dueDate.get() != current.dueDate
        body.type.ifPresent { current.type = it }
        body.title.ifPresent { current.title = it }
        body.description.ifPresent { current.description = it }
        body.priority.ifPresent { current.priority = it }
        body.dueDate.ifPresent { current.dueDate = it }
        body.parcelId.ifPresent { current.parcelId = it }
        body.assigneeId.ifPresent { current.assigneeId = it }
        if (dueChanged) {
            current.reminderSentAt = null
        }
        tasks.saveAndFlush(current)
        val updated = load(id)
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.TASK_UPDATED, updated)
        return ApiResponse.ok(updated)
    }

    @PostMapping("/{id}/status")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Change the status. Operators can only change their own tasks.")
    fun status(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: StatusRequest): ResponseEntity<ApiResponse<TaskWithRefs>> {
        val current = requireTask(auth.farmId(), id)
        if (auth.role() == Role.OPERATOR && auth.userId() != current.assigneeId) {
            throw Errors.forbidden("You can only update tasks assigned to you.")
        }
        current.status = body.status
        current.completedAt = if (body.status == TaskStatus.DONE) Times.now() else null
        tasks.saveAndFlush(current)
        val updated = load(id)
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.TASK_UPDATED, updated)
        return ApiResponse.ok(updated)
    }

    @DeleteMapping("/{id}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Delete a task")
    fun delete(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<Void> {
        requireTask(auth.farmId(), id)
        tasks.deleteById(id)
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.TASK_UPDATED, linkedMapOf("id" to id, "deleted" to true))
        return ApiResponse.noContent()
    }

    companion object {
        @JvmField
        val TASK_LABELS: Map<TaskType, String> = EnumMap<TaskType, String>(TaskType::class.java).apply {
            put(TaskType.FERTILIZATION, "Fertilization")
            put(TaskType.HERBICIDE, "Herbicide application")
            put(TaskType.FUNGICIDE, "Fungicide application")
            put(TaskType.INSECTICIDE, "Insecticide application")
            put(TaskType.PEST_CHECK, "Pest check")
            put(TaskType.DISEASE_CHECK, "Disease check")
            put(TaskType.MOISTURE_CHECK, "Moisture check")
            put(TaskType.LEAF_ANALYSIS, "Leaf analysis")
            put(TaskType.HARVEST, "Harvest")
            put(TaskType.SOIL_PREP, "Seedbed preparation")
            put(TaskType.SEED_PURCHASE, "Seed purchase")
            put(TaskType.SOWING, "Sowing")
            put(TaskType.EMERGENCE_CHECK, "Emergence check")
            put(TaskType.HARVEST_PREP, "Harvest preparation")
        }
    }
}
