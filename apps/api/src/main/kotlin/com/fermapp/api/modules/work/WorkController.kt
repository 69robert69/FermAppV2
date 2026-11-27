package com.fermapp.api.modules.work

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.model.Role
import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.domain.model.WorkType
import com.fermapp.api.domain.work.WorkMath
import com.fermapp.api.domain.work.WorkMath.WorkEstimate
import com.fermapp.api.domain.work.WorkMath.WorkParams
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.MachineLocationView
import com.fermapp.api.modules.work.WorkViews.WorkView
import com.fermapp.api.persistence.entity.MachineLocation
import com.fermapp.api.persistence.entity.WorkSession
import com.fermapp.api.persistence.repository.MachineLocationRepository
import com.fermapp.api.persistence.repository.WorkSessionRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.criteria.Predicate
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Field operations with live progress (work.routes.ts). */
@RestController
@RequestMapping("/api/v1/work")
@Tag(name = "Work")
class WorkController(
    private val works: WorkSessionRepository,
    private val locations: MachineLocationRepository,
    private val service: WorkService,
    private val access: AccessGuard,
    private val realtime: RealtimeHub,
) {

    class EstimateRequest(
        @field:NotNull val parcelId: UUID?,
        @field:NotNull @field:Positive @field:DecimalMax("100") val workingWidthM: Double?,
        @field:NotNull @field:Positive @field:DecimalMax("60") val speedKmh: Double?,
        @field:NotNull @field:Positive @field:DecimalMax("100") val efficiencyPct: Double?,
    )

    @JvmRecord
    data class EstimateResponse(val areaHa: Double, val capacityHaH: Double, val durationHours: Double, val eta: Instant)

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("parcelId", "machineId", "operatorId", "type", "notes", "workingWidthM", "speedKmh", "efficiencyPct")
    class StartRequest(
        @field:NotNull val parcelId: UUID?,
        val machineId: UUID?,
        val operatorId: UUID?,
        @field:NotNull val type: WorkType?,
        notes: String?,
        @field:NotNull @field:Positive @field:DecimalMax("100") val workingWidthM: Double?,
        @field:NotNull @field:Positive @field:DecimalMax("60") val speedKmh: Double?,
        @field:NotNull @field:Positive @field:DecimalMax("100") val efficiencyPct: Double?,
    ) {
        @field:Size(max = 1000)
        val notes: String? = Text.trim(notes)
    }

    class LocationRequest(
        @field:NotNull @field:DecimalMin("-90") @field:DecimalMax("90") val lat: Double?,
        @field:NotNull @field:DecimalMin("-180") @field:DecimalMax("180") val lng: Double?,
        @field:DecimalMin("0") @field:DecimalMax("360") val headingDeg: Double?,
        @field:DecimalMin("0") @field:DecimalMax("200") val speedKmh: Double?,
        @field:DecimalMin("0") @field:DecimalMax("100000") val accuracyM: Double?,
    )

    /** The latest position, with `workId`. */
    @JvmRecord
    data class LocationResponse(
        val id: String?,
        val workSessionId: UUID?,
        val machineId: UUID?,
        val lat: Double,
        val lng: Double,
        val headingDeg: Double?,
        val speedKmh: Double?,
        val accuracyM: Double?,
        val recordedAt: Instant?,
        val workId: UUID,
    ) {
        companion object {
            fun of(view: MachineLocationView, workId: UUID): LocationResponse = LocationResponse(
                view.id, view.workSessionId, view.machineId, view.lat, view.lng, view.headingDeg, view.speedKmh, view.accuracyM,
                view.recordedAt, workId,
            )
        }
    }

    private fun requireControllableWork(auth: AuthContext, id: UUID): WorkSession {
        val work = works.findByIdAndFarmId(id, auth.farmId()).orElseThrow { Errors.notFound("Operation") }
        if (auth.role() == Role.OPERATOR && work.operatorId != auth.userId()) {
            throw Errors.forbidden("You can only control your own operations.")
        }
        return work
    }

    private fun estimate(areaHa: Double, width: Double, speed: Double, efficiency: Double): WorkEstimate = try {
        WorkMath.estimateWork(areaHa, WorkParams(width, speed, efficiency), Times.now())
    } catch (error: IllegalArgumentException) {
        throw Errors.badRequest(error.message ?: "")
    }

    @PostMapping("/estimate")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Calculate capacity, duration and ETA")
    fun estimate(auth: AuthContext, @Valid @RequestBody body: EstimateRequest): ResponseEntity<ApiResponse<EstimateResponse>> {
        val parcel = access.requireActiveParcel(auth.farmId(), body.parcelId!!)
        val result = estimate(parcel.areaHa, body.workingWidthM!!, body.speedKmh!!, body.efficiencyPct!!)
        return ApiResponse.ok(EstimateResponse(parcel.areaHa, result.capacityHaH, result.durationHours, result.eta))
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List operations")
    @Transactional(readOnly = true)
    fun list(
        auth: AuthContext,
        @RequestParam(required = false) status: RunStatus?,
        @RequestParam(required = false) parcelId: UUID?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
    ): ResponseEntity<ApiResponse<List<WorkView>>> {
        val spec = Specification<WorkSession> { root, _, cb ->
            val predicates = mutableListOf<Predicate>(cb.equal(root.get<UUID>("farmId"), auth.farmId()))
            if (status != null) {
                predicates += cb.equal(root.get<RunStatus>("status"), status)
            }
            if (parcelId != null) {
                predicates += cb.equal(root.get<UUID>("parcelId"), parcelId)
            }
            cb.and(*predicates.toTypedArray())
        }
        val now = Times.now()
        return ApiResponse.ok(
            works.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "startedAt"))).content.map { WorkView.of(it, now) },
        )
    }

    @PostMapping
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Start an operation")
    fun start(auth: AuthContext, @Valid @RequestBody body: StartRequest): ResponseEntity<ApiResponse<WorkView>> {
        val parcel = access.requireActiveParcel(auth.farmId(), body.parcelId!!)
        if (body.machineId != null) {
            access.requireMachine(auth.farmId(), body.machineId)
        }
        // Operators can only start operations in their own name.
        val operatorId = if (auth.role() == Role.OPERATOR) auth.userId() else body.operatorId ?: auth.userId()
        access.requireMember(auth.farmId(), operatorId)
        if (body.machineId != null && works.existsByMachineIdAndStatusIn(body.machineId, WorkService.ACTIVE)) {
            throw Errors.conflict("The machine is already used in an active operation.")
        }
        val width = body.workingWidthM!!
        val speed = body.speedKmh!!
        val efficiency = body.efficiencyPct!!
        val estimate = estimate(parcel.areaHa, width, speed, efficiency)
        val work = WorkSession()
        work.farmId = auth.farmId()
        work.parcelId = parcel.id
        work.machineId = body.machineId
        work.operatorId = operatorId
        work.type = body.type!!
        work.notes = body.notes
        work.areaHa = parcel.areaHa
        work.workingWidthM = width
        work.speedKmh = speed
        work.efficiencyPct = efficiency
        work.capacityHaH = estimate.capacityHaH
        work.durationHours = parcel.areaHa / (width * speed * (efficiency / 100) / 10)
        works.saveAndFlush(work)
        return ApiResponse.created(service.publish(work.id))
    }

    @GetMapping("/{id}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Details and progress of an operation")
    @Transactional(readOnly = true)
    fun get(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<WorkView>> {
        val work = works.findByIdAndFarmId(id, auth.farmId()).orElseThrow { Errors.notFound("Operation") }
        return ApiResponse.ok(WorkView.of(work, Times.now()))
    }

    @PostMapping("/{id}/location")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Send the machine’s GPS position")
    fun sendLocation(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: LocationRequest): ResponseEntity<Void> {
        val work = requireControllableWork(auth, id)
        if (work.status !in WorkService.ACTIVE) {
            throw Errors.conflict("The operation has ended.")
        }
        val location = MachineLocation()
        location.workSessionId = work.id
        location.machineId = work.machineId
        location.lat = body.lat!!
        location.lng = body.lng!!
        location.headingDeg = body.headingDeg
        location.speedKmh = body.speedKmh
        location.accuracyM = body.accuracyM
        locations.save(location)
        val payload = linkedMapOf<String, Any?>(
            "workId" to work.id,
            "parcelId" to work.parcelId,
            "machineId" to work.machineId,
            "lat" to location.lat,
            "lng" to location.lng,
            "headingDeg" to location.headingDeg,
            "speedKmh" to location.speedKmh,
            "recordedAt" to location.recordedAt,
        )
        realtime.emitToFarm(auth.farmId(), RealtimeEvent.MACHINE_LOCATION, payload)
        return ApiResponse.noContent()
    }

    @GetMapping("/{id}/location")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Latest GPS position of the machine")
    fun lastLocation(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<LocationResponse?>> {
        works.findByIdAndFarmId(id, auth.farmId()).orElseThrow { Errors.notFound("Operation") }
        val latest = locations.findFirstByWorkSessionIdOrderByRecordedAtDesc(id).orElse(null)
        return ApiResponse.ok(latest?.let { LocationResponse.of(MachineLocationView.of(it)!!, id) })
    }

    @PostMapping("/{id}/pause")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Pause the operation")
    fun pause(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<WorkView>> {
        requireControllableWork(auth, id)
        if (works.pause(id, Times.now()) == 0) {
            throw Errors.conflict("Only a running operation can be paused.")
        }
        return ApiResponse.ok(service.publish(id))
    }

    @PostMapping("/{id}/resume")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Resume the operation")
    fun resume(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<WorkView>> {
        val work = requireControllableWork(auth, id)
        val pausedAt = work.pausedAt
        if (work.status != RunStatus.PAUSED || pausedAt == null) {
            throw Errors.conflict("The operation is not paused.")
        }
        val now = Times.now()
        works.resume(id, now.toEpochMilli() - pausedAt.toEpochMilli(), now)
        return ApiResponse.ok(service.publish(id))
    }

    @PostMapping("/{id}/complete")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Complete the operation")
    fun complete(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<WorkView>> {
        requireControllableWork(auth, id)
        if (service.complete(id, Times.now()) == null) {
            throw Errors.conflict("The operation has already ended.")
        }
        return ApiResponse.ok(service.publish(id))
    }

    @PostMapping("/{id}/cancel")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Cancel the operation")
    fun cancel(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<WorkView>> {
        requireControllableWork(auth, id)
        if (works.cancel(id, Times.now(), WorkService.ACTIVE) == 0) {
            throw Errors.conflict("The operation has already ended.")
        }
        return ApiResponse.ok(service.publish(id))
    }
}
