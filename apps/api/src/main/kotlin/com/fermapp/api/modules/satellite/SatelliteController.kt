package com.fermapp.api.modules.satellite

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Concurrency
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.crops.CropRules
import com.fermapp.api.domain.crops.CropRules.VegetationHealth
import com.fermapp.api.integrations.SentinelClient
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.SatelliteView
import com.fermapp.api.persistence.entity.SatelliteObservation
import com.fermapp.api.persistence.repository.SatelliteObservationRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Sentinel-2 history, refresh, comparison and index images (satellite.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels/{id}/satellite")
@Tag(name = "Satellite")
class SatelliteController(
    private val access: AccessGuard,
    private val observations: SatelliteObservationRepository,
    private val sentinel: SentinelClient,
) {

    @JvmRecord
    data class ObservationWithHealth(@get:JsonUnwrapped val observation: SatelliteView, val health: VegetationHealth) {
        companion object {
            fun of(item: SatelliteObservation): ObservationWithHealth =
                ObservationWithHealth(SatelliteView.of(item), CropRules.vegetationHealth(item.ndvi, item.ndre))
        }
    }

    @JvmRecord
    data class HistoryResponse(val configured: Boolean, val observations: List<ObservationWithHealth>)

    class RefreshRequest(days: Int?) {
        @field:Min(1) @field:Max(365)
        val days: Int = days ?: 60
    }

    @JvmRecord
    data class RefreshResponse(val imported: Int)

    @JvmRecord
    data class Change(val ndvi: Double, val ndre: Double?, val healthIndex: Int)

    @JvmRecord
    data class CompareResponse(val before: ObservationWithHealth, val after: ObservationWithHealth, val change: Change)

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "NDVI, NDRE and Health Index history")
    fun history(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "60") @Min(1) @Max(200) limit: Int,
    ): ResponseEntity<ApiResponse<HistoryResponse>> {
        access.requireParcel(auth.farmId(), id)
        val items = observations.findByParcelIdOrderByDateDesc(id, PageRequest.of(0, limit)).map { ObservationWithHealth.of(it) }
        return ApiResponse.ok(HistoryResponse(sentinel.isConfigured(), items))
    }

    @PostMapping("/refresh")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Download Sentinel-2 observations for the given period")
    fun refresh(
        auth: AuthContext,
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) body: RefreshRequest?,
    ): ResponseEntity<ApiResponse<RefreshResponse>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        if (!sentinel.isConfigured()) {
            throw Errors.notConfigured("The satellite imagery service")
        }
        val days = body?.days ?: 60
        val to = Instant.now()
        val from = to.minusMillis(days * Times.DAY_MS)
        val found = sentinel.fetchObservations(parcel.geometry, Times.isoDay(from)!!, Times.isoDay(to)!!)
        Concurrency.mapLimit(found, found.size) { item ->
            val health = CropRules.vegetationHealth(item.ndvi, item.ndre)
            val date = LocalDate.parse(item.date)
            val row = observations.findByParcelIdAndDate(parcel.id, date).orElseGet {
                SatelliteObservation().apply {
                    parcelId = parcel.id
                    this.date = date
                }
            }
            row.ndvi = item.ndvi
            row.ndre = item.ndre
            row.cloudPct = item.cloudPct
            row.healthIndex = health.healthIndex
            row.healthClass = health.healthClass.name
            observations.save(row)
        }
        return ApiResponse.ok(RefreshResponse(found.size))
    }

    @GetMapping("/compare")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Compare two observation dates")
    fun compare(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam @Pattern(regexp = ISO_DATE, message = ISO_DATE_MESSAGE) from: String,
        @RequestParam @Pattern(regexp = ISO_DATE, message = ISO_DATE_MESSAGE) to: String,
    ): ResponseEntity<ApiResponse<CompareResponse>> {
        access.requireParcel(auth.farmId(), id)
        val before = observations.findByParcelIdAndDate(id, LocalDate.parse(from)).orElse(null)
        val after = observations.findByParcelIdAndDate(id, LocalDate.parse(to)).orElse(null)
        if (before == null || after == null) {
            throw Errors.notFound("The observation for one of the dates")
        }
        val beforeNdre = before.ndre
        val afterNdre = after.ndre
        val change = Change(
            Math.round((after.ndvi - before.ndvi) * 1000) / 1000.0,
            if (beforeNdre != null && afterNdre != null) Math.round((afterNdre - beforeNdre) * 1000) / 1000.0 else null,
            after.healthIndex - before.healthIndex,
        )
        return ApiResponse.ok(CompareResponse(ObservationWithHealth.of(before), ObservationWithHealth.of(after), change))
    }

    @GetMapping("/image")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Coloured index image for a date")
    fun image(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam @Pattern(regexp = ISO_DATE, message = ISO_DATE_MESSAGE) date: String,
        @RequestParam(defaultValue = "NDVI") @Pattern(regexp = "NDVI|NDRE") index: String,
    ): ResponseEntity<ByteArray> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val image = sentinel.fetchIndexImage(parcel.geometry, date, index)
        return ApiResponse.file(image, "image/png", "$index-$date.png", true)
    }

    private companion object {
        const val ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}$"
        const val ISO_DATE_MESSAGE = "The date must have the format YYYY-MM-DD."
    }
}
