package com.fermapp.api.modules.weather

import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.domain.weather.WeatherRules
import com.fermapp.api.domain.weather.WeatherRules.FieldOperation
import com.fermapp.api.domain.weather.WeatherRules.WorkWindow
import com.fermapp.api.integrations.OpenMeteoClient
import com.fermapp.api.integrations.OpenMeteoClient.ForecastHourFull
import com.fermapp.api.integrations.OpenMeteoClient.WeatherDay
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.AlertView
import com.fermapp.api.modules.common.Views.AlertWithParcel
import com.fermapp.api.persistence.repository.WeatherAlertRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Forecast, weather history, work windows and alerts (weather.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Weather")
class WeatherController(
    private val access: AccessGuard,
    private val openMeteo: OpenMeteoClient,
    private val alerts: WeatherAlertRepository,
    private val weather: WeatherService,
) {

    @JvmRecord
    data class ForecastResponse(
        val timezone: String?,
        val range: String,
        val current: ForecastHourFull?,
        val hours: List<ForecastHourFull>,
        val days: List<WeatherDay>,
    )

    @JvmRecord
    data class Totals(val precipitationMm: Double, val et0Mm: Double)

    @JvmRecord
    data class HistoryResponse(val range: String, val totals: Totals, val days: List<WeatherDay>)

    @JvmRecord
    data class OperationWindows(val operation: FieldOperation, val message: String, val windows: List<WorkWindow>)

    @GetMapping("/parcels/{id}/weather/forecast")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Forecast for 24 hours, 7 or 14 days")
    fun forecast(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "7d") @Pattern(regexp = "24h|7d|14d") range: String,
    ): ResponseEntity<ApiResponse<ForecastResponse>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val forecast = openMeteo.getForecast(parcel.centerLat, parcel.centerLng)
        val now = Times.now()
        val hours = OpenMeteoClient.upcomingHours(forecast, now)
        val today = OpenMeteoClient.localHour(forecast, now).substring(0, 10)
        val days = forecast.days.filter { it.date >= today }
        val dayRange = range != "24h"
        return ApiResponse.ok(
            ForecastResponse(
                forecast.timezone,
                range,
                hours.firstOrNull(),
                if (dayRange) listOf() else hours.subList(0, Math.min(24, hours.size)),
                if (dayRange) days.subList(0, Math.min(if (range == "7d") 7 else 14, days.size)) else listOf(),
            ),
        )
    }

    @GetMapping("/parcels/{id}/weather/history")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Weather history for 30 days or 12 months")
    fun history(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "30d") @Pattern(regexp = "30d|12m") range: String,
    ): ResponseEntity<ApiResponse<HistoryResponse>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val end = Instant.now().minusMillis(2 * Times.DAY_MS).atZone(ZoneOffset.UTC)
        val start = if (range == "30d") end.minusDays(29) else end.minusYears(1)
        val days = openMeteo.getHistory(
            parcel.centerLat, parcel.centerLng, Times.isoDay(start.toInstant())!!, Times.isoDay(end.toInstant())!!,
        )
        val totals = Totals(Js.round1(days.sumOfDoubles { it.precipitationMm }), Js.round1(days.sumOfDoubles { it.et0Mm ?: 0.0 }))
        return ApiResponse.ok(HistoryResponse(range, totals, days))
    }

    @GetMapping("/parcels/{id}/weather/windows")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Optimal windows for field work over the next 7 days")
    fun windows(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<List<OperationWindows>>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val forecast = openMeteo.getForecast(parcel.centerLat, parcel.centerLng)
        val now = Times.now()
        val from = OpenMeteoClient.localHour(forecast, now)
        val upcoming = OpenMeteoClient.upcomingHours(forecast, now)
        val horizon = if (upcoming.size > 168) upcoming[168].time() else null
        val hours = forecast.hours.filter { horizon == null || it.time() < horizon }
        return ApiResponse.ok(
            OPERATIONS.map { operation ->
                val windows = WeatherRules.findWorkWindows(operation, hours, 2, from)
                OperationWindows(operation, WeatherRules.bestWindowMessage(operation, windows), windows.subList(0, Math.min(5, windows.size)))
            },
        )
    }

    @GetMapping("/alerts")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Farm weather alerts")
    @Transactional(readOnly = true)
    fun list(
        auth: AuthContext,
        @RequestParam(required = false) parcelId: UUID?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
    ): ResponseEntity<ApiResponse<List<AlertWithParcel>>> {
        val page = PageRequest.of(0, limit)
        val found = if (parcelId == null) alerts.findLatest(auth.farmId(), page) else alerts.findLatestForParcel(auth.farmId(), parcelId, page)
        return ApiResponse.ok(found.map { AlertWithParcel.of(it) })
    }

    @PostMapping("/parcels/{id}/alerts/check")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Check weather alerts for the parcel now")
    fun check(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<ApiResponse<List<AlertView>>> =
        ApiResponse.ok(weather.checkParcelAlerts(access.requireParcel(auth.farmId(), id)))

    private companion object {
        val OPERATIONS = listOf(FieldOperation.SPRAYING, FieldOperation.FERTILIZING, FieldOperation.SOWING, FieldOperation.HARVESTING)
    }
}
