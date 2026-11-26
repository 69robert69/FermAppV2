package com.fermapp.api.modules.tasks;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.model.TaskPriority;
import com.fermapp.api.domain.model.TaskStatus;
import com.fermapp.api.domain.model.TaskType;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.TaskWithRefs;
import com.fermapp.api.modules.notifications.NotificationService;
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput;
import com.fermapp.api.persistence.entity.Task;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tasks (tasks.routes.ts). Operators can only change the status of their own tasks. */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "To Do")
public class TaskController {

    public static final Map<TaskType, String> TASK_LABELS = new EnumMap<>(TaskType.class);

    static {
        TASK_LABELS.put(TaskType.FERTILIZATION, "Fertilization");
        TASK_LABELS.put(TaskType.HERBICIDE, "Herbicide application");
        TASK_LABELS.put(TaskType.FUNGICIDE, "Fungicide application");
        TASK_LABELS.put(TaskType.INSECTICIDE, "Insecticide application");
        TASK_LABELS.put(TaskType.PEST_CHECK, "Pest check");
        TASK_LABELS.put(TaskType.DISEASE_CHECK, "Disease check");
        TASK_LABELS.put(TaskType.MOISTURE_CHECK, "Moisture check");
        TASK_LABELS.put(TaskType.LEAF_ANALYSIS, "Leaf analysis");
        TASK_LABELS.put(TaskType.HARVEST, "Harvest");
        TASK_LABELS.put(TaskType.SOIL_PREP, "Seedbed preparation");
        TASK_LABELS.put(TaskType.SEED_PURCHASE, "Seed purchase");
        TASK_LABELS.put(TaskType.SOWING, "Sowing");
        TASK_LABELS.put(TaskType.EMERGENCE_CHECK, "Emergence check");
        TASK_LABELS.put(TaskType.HARVEST_PREP, "Harvest preparation");
    }

    public record TaskTypeOption(TaskType code, String label) {
    }

    public record TaskCreate(@NotNull TaskType type, @Size(min = 1, max = 160) String title,
                             @Size(max = 2000) String description, TaskPriority priority, @NotNull Instant dueDate,
                             UUID parcelId, UUID assigneeId) {
        public TaskCreate {
            title = Text.trim(title);
            description = Text.trim(description);
            priority = priority == null ? TaskPriority.MEDIUM : priority;
        }
    }

    public record TaskUpdate(JsonNullable<@NotNull TaskType> type,
                             JsonNullable<@NotNull @Size(min = 1, max = 160) String> title,
                             JsonNullable<@Size(max = 2000) String> description,
                             JsonNullable<@NotNull TaskPriority> priority,
                             JsonNullable<@NotNull Instant> dueDate,
                             JsonNullable<UUID> parcelId,
                             JsonNullable<UUID> assigneeId) {
        public TaskUpdate {
            type = orUndefined(type);
            title = title == null || !title.isPresent() ? JsonNullable.undefined() : JsonNullable.of(Text.trim(title.get()));
            description = description == null || !description.isPresent()
                    ? JsonNullable.undefined() : JsonNullable.of(Text.trim(description.get()));
            priority = orUndefined(priority);
            dueDate = orUndefined(dueDate);
            parcelId = orUndefined(parcelId);
            assigneeId = orUndefined(assigneeId);
        }

        private static <T> JsonNullable<T> orUndefined(JsonNullable<T> value) {
            return value == null ? JsonNullable.undefined() : value;
        }
    }

    public record StatusRequest(@NotNull TaskStatus status) {
    }

    private final TaskRepository tasks;
    private final AccessGuard access;
    private final RealtimeHub realtime;
    private final NotificationService notifications;
    private final TransactionTemplate tx;

    public TaskController(TaskRepository tasks, AccessGuard access, RealtimeHub realtime, NotificationService notifications,
                          TransactionTemplate tx) {
        this.tasks = tasks;
        this.access = access;
        this.realtime = realtime;
        this.notifications = notifications;
        this.tx = tx;
    }

    private Task requireTask(UUID farmId, UUID id) {
        return tasks.findByIdAndFarmId(id, farmId).orElseThrow(() -> Errors.notFound("Task"));
    }

    /** Reads the task with its parcel and assignee in a fresh persistence context. */
    private TaskWithRefs load(UUID id) {
        return tx.execute(status -> TaskWithRefs.of(tasks.findById(id).orElseThrow(Errors::recordNotFound)));
    }

    @GetMapping("/types")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Predefined tasks")
    public ResponseEntity<ApiResponse<List<TaskTypeOption>>> types() {
        return ApiResponse.ok(TASK_LABELS.entrySet().stream().map(e -> new TaskTypeOption(e.getKey(), e.getValue())).toList());
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List tasks")
    public ResponseEntity<ApiResponse<List<TaskWithRefs>>> list(AuthContext auth,
                                                               @RequestParam(required = false) TaskStatus status,
                                                               @RequestParam(required = false) UUID parcelId,
                                                               @RequestParam(defaultValue = "false") @Pattern(regexp = "true|false") String mine) {
        Specification<Task> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("farmId"), auth.farmId()));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (parcelId != null) {
                predicates.add(cb.equal(root.get("parcelId"), parcelId));
            }
            if ("true".equals(mine)) {
                predicates.add(cb.equal(root.get("assigneeId"), auth.userId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return ApiResponse.ok(tx.execute(s -> tasks.findAll(spec,
                        PageRequest.of(0, 500, Sort.by(Sort.Order.asc("status"), Sort.Order.asc("dueDate"))))
                .stream().map(TaskWithRefs::of).toList()));
    }

    @PostMapping
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Create a task")
    public ResponseEntity<ApiResponse<TaskWithRefs>> create(AuthContext auth, @Valid @RequestBody TaskCreate body) {
        if (body.parcelId() != null) {
            access.requireParcel(auth.farmId(), body.parcelId());
        }
        if (body.assigneeId() != null) {
            access.requireMember(auth.farmId(), body.assigneeId());
        }
        Task task = new Task();
        task.setFarmId(auth.farmId());
        task.setType(body.type());
        task.setTitle(body.title() != null ? body.title() : TASK_LABELS.get(body.type()));
        task.setDescription(body.description());
        task.setPriority(body.priority());
        task.setDueDate(body.dueDate());
        task.setParcelId(body.parcelId());
        task.setAssigneeId(body.assigneeId());
        tasks.saveAndFlush(task);
        TaskWithRefs created = load(task.getId());
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.TASK_UPDATED, created);
        if (task.getAssigneeId() != null && !task.getAssigneeId().equals(auth.userId())) {
            notifications.notify(new NotificationInput(auth.farmId(), List.of(task.getAssigneeId()), null, "New task",
                    created.task().title() + (created.parcel() != null ? " on parcel " + created.parcel().name() : "") + ".",
                    Map.of("type", "task", "id", task.getId().toString())));
        }
        return ApiResponse.created(created);
    }

    @PatchMapping("/{id}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Update a task")
    public ResponseEntity<ApiResponse<TaskWithRefs>> update(AuthContext auth, @PathVariable UUID id, @Valid @RequestBody TaskUpdate body) {
        Task current = requireTask(auth.farmId(), id);
        if (body.parcelId().isPresent() && body.parcelId().get() != null) {
            access.requireParcel(auth.farmId(), body.parcelId().get());
        }
        if (body.assigneeId().isPresent() && body.assigneeId().get() != null) {
            access.requireMember(auth.farmId(), body.assigneeId().get());
        }
        boolean dueChanged = body.dueDate().isPresent() && !body.dueDate().get().equals(current.getDueDate());
        body.type().ifPresent(current::setType);
        body.title().ifPresent(current::setTitle);
        body.description().ifPresent(current::setDescription);
        body.priority().ifPresent(current::setPriority);
        body.dueDate().ifPresent(current::setDueDate);
        body.parcelId().ifPresent(current::setParcelId);
        body.assigneeId().ifPresent(current::setAssigneeId);
        if (dueChanged) {
            current.setReminderSentAt(null);
        }
        tasks.saveAndFlush(current);
        TaskWithRefs updated = load(id);
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.TASK_UPDATED, updated);
        return ApiResponse.ok(updated);
    }

    @PostMapping("/{id}/status")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Change the status. Operators can only change their own tasks.")
    public ResponseEntity<ApiResponse<TaskWithRefs>> status(AuthContext auth, @PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
        Task current = requireTask(auth.farmId(), id);
        if (auth.role() == Role.OPERATOR && !auth.userId().equals(current.getAssigneeId())) {
            throw Errors.forbidden("You can only update tasks assigned to you.");
        }
        current.setStatus(body.status());
        current.setCompletedAt(body.status() == TaskStatus.DONE ? Times.now() : null);
        tasks.saveAndFlush(current);
        TaskWithRefs updated = load(id);
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.TASK_UPDATED, updated);
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/{id}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Delete a task")
    public ResponseEntity<Void> delete(AuthContext auth, @PathVariable UUID id) {
        requireTask(auth.farmId(), id);
        tasks.deleteById(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("deleted", true);
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.TASK_UPDATED, payload);
        return ApiResponse.noContent();
    }
}
