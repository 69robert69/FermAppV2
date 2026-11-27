package com.fermapp.api.modules.irrigation

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.crops.CropRules
import com.fermapp.api.domain.crops.CropRules.WaterBalance
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.model.IrrigationSystemType
import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.domain.work.WorkMath
import com.fermapp.api.domain.work.WorkMath.IrrigationEstimate
import com.fermapp.api.integrations.OpenMeteoClient
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.work.WorkService
import com.fermapp.api.modules.work.WorkViews.IrrigationView
import com.fermapp.api.persistence.entity.IrrigationRun
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.IrrigationRunRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.criteria.Predicate
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
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

/** Irrigation runs and the seasonal water balance (irrigation.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Irrigation")
class IrrigationController(
    private val runs: IrrigationRunRepository,
    private val seasons: CropSeasonRepository,
    private val service: IrrigationService,
    private val access: AccessGuard,
    private val weather: OpenMeteoClient,
) {

    class RouteRequest(
        @field:NotNull @field:Positive @field:DecimalMax("100000") val routeLengthM: Double?,
        @field:NotNull @field:Positive @field:DecimalMax("10000") val speedMPerH: Double?,
    )

    class StartRequest(
        @field:NotNull val parcelId: UUID?,
        @field:NotNull val systemType: IrrigationSystemType?,
        @field:Positive @field:DecimalMax("200") val doseMm: Double?,
        @field:NotNull @field:Positive @field:DecimalMax("100000") val routeLengthM: Double?,
        @field:NotNull @field:Positive @field:DecimalMax("10000") val speedMPerH: Double?,
    )

    @JvmRecord
    data class Period(val start: String, val end: String)

    @JvmRecord
    data class WaterResponse(
        val year: Int,
        val cropName: String,
        val period: Period,
        val precipitationMm: Double,
        val irrigationMm: Double,
        @get:JsonUnwrapped val balance: WaterBalance,
    )

    private fun requireRun(farmId: UUID, id: UUID): IrrigationRun =
        runs.findByIdAndFarmId(id, farmId).orElseThrow { Errors.notFound("Irrigation") }

    @PostMapping("/irrigation/estimate")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Calculate irrigation duration and ETA")
    fun estimate(@Valid @RequestBody body: RouteRequest): ResponseEntity<ApiResponse<IrrigationEstimate>> =
        ApiResponse.ok(WorkMath.estimateIrrigation(body.routeLengthM!!, body.speedMPerH!!, Times.now()))

    @GetMapping("/irrigation")
    @Access(AccessGroup.ALL)
    @Operation(summary = "List irrigations")
    @Transactional(readOnly = true)
    fun list(
        auth: AuthContext,
        @RequestParam(required = false) status: RunStatus?,
        @RequestParam(required = false) parcelId: UUID?,
    ): ResponseEntity<ApiResponse<List<IrrigationView>>> {
        val spec = Specification<IrrigationRun> { root, _, cb ->
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
            runs.findAll(spec, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "startedAt"))).content.map { IrrigationView.of(it, now) },
        )
    }

    @PostMapping("/irrigation")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Start an irrigation")
    fun start(auth: AuthContext, @Valid @RequestBody body: StartRequest): ResponseEntity<ApiResponse<IrrigationView>> {
        val parcelId = body.parcelId!!
        access.requireActiveParcel(auth.farmId(), parcelId)
        if (runs.existsByParcelIdAndStatusIn(parcelId, WorkService.ACTIVE)) {
            throw Errors.conflict("The parcel already has an active irrigation.")
        }
        val run = IrrigationRun()
        run.farmId = auth.farmId()
        run.parcelId = parcelId
        run.systemType = body.systemType
        run.doseMm = body.doseMm
        run.routeLengthM = body.routeLengthM!!
        run.speedMPerH = body.speedMPerH!!
        run.durationHours = body.routeLengthM / body.speedMPerH
        runs.saveAndFlush(run)
        return ApiResponse.created(service.publish(run.id))
    }

    @GetMapping("/irrigation/{id}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Irrigation details and progress")
    fun get(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<IrrigationView>> {
        requireRun(auth.farmId(), id)
        return ApiResponse.ok(service.publish(id))
    }

    @PostMapping("/irrigation/{id}/pause")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Pause the irrigation")
    fun pause(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<IrrigationView>> {
        requireRun(auth.farmId(), id)
        if (runs.pause(id, Times.now()) == 0) {
            throw Errors.conflict("Only a running irrigation can be paused.")
        }
        return ApiResponse.ok(service.publish(id))
    }

    @PostMapping("/irrigation/{id}/resume")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Resume the irrigation")
    fun resume(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<IrrigationView>> {
        val run = requireRun(auth.farmId(), id)
        val pausedAt = run.pausedAt
        if (run.status != RunStatus.PAUSED || pausedAt == null) {
            throw Errors.conflict("The irrigation is not paused.")
        }
        val now = Times.now()
        runs.resume(id, now.toEpochMilli() - pausedAt.toEpochMilli(), now)
        return ApiResponse.ok(service.publish(id))
    }

    @PostMapping("/irrigation/{id}/complete")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Complete the irrigation")
    fun complete(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<IrrigationView>> {
        requireRun(auth.farmId(), id)
        if (service.complete(id, Times.now()) == null) {
            throw Errors.conflict("The irrigation has already ended.")
        }
        return ApiResponse.ok(service.publish(id))
    }

    @PostMapping("/irrigation/{id}/cancel")
    @Access(AccessGroup.FIELD)
    @Operation(summary = "Cancel the irrigation")
    fun cancel(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<IrrigationView>> {
        requireRun(auth.farmId(), id)
        if (runs.cancel(id, Times.now(), WorkService.ACTIVE) == 0) {
            throw Errors.conflict("The irrigation has already ended.")
        }
        return ApiResponse.ok(service.publish(id))
    }

    @GetMapping("/parcels/{id}/water")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Water requirement, rainfall received and deficit")
    fun water(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(required = false) @Min(2000) @Max(2100) year: Int?,
    ): ResponseEntity<ApiResponse<WaterResponse>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val target = year ?: Times.currentLocalYear()
        val season = seasons.findByParcelIdAndYear(parcel.id, target).orElseThrow { Errors.notFound("The crop for $target") }

        // Without sowing and harvest dates, the April-September growing season is used.
        val start = Times.isoDay(season.sowingDate ?: Times.utc(target, 4, 1, 0))!!
        val latestAvailable = Instant.now().minusMillis(2 * Times.DAY_MS)
        val seasonEnd = season.harvestDate ?: Times.utc(target, 9, 30, 0)
        val end = Times.isoDay(if (seasonEnd.isBefore(latestAvailable)) seasonEnd else latestAvailable)!!

        val days = if (start <= end) weather.getHistory(parcel.centerLat, parcel.centerLng, start, end) else listOf()
        val precipitationMm = days.sumOfDoubles { it.precipitationMm }
        val irrigationMm = runs.findCompletedWithDoseSince(parcel.id, Times.utcMidnight(start)).sumOfDoubles { it.doseMm * it.progressPct / 100 }
        return ApiResponse.ok(
            WaterResponse(
                target, Crops.name(season.crop), Period(start, end), Js.round1(precipitationMm), Js.round1(irrigationMm),
                CropRules.waterBalance(season.crop, precipitationMm, irrigationMm, 30.0),
            ),
        )
    }
}
