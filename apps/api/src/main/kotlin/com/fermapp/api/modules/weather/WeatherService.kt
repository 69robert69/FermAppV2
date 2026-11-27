package com.fermapp.api.modules.weather

import com.fermapp.api.common.error.GlobalExceptionHandler
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.weather.WeatherRules
import com.fermapp.api.domain.weather.WeatherRules.ForecastHour
import com.fermapp.api.domain.weather.WeatherRules.WeatherAlertDraft
import com.fermapp.api.integrations.OpenMeteoClient
import com.fermapp.api.integrations.OpenMeteoClient.Forecast
import com.fermapp.api.integrations.OpenWeatherClient
import com.fermapp.api.modules.common.Views.AlertView
import com.fermapp.api.modules.notifications.NotificationService
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput
import com.fermapp.api.persistence.entity.Parcel
import com.fermapp.api.persistence.entity.WeatherAlert
import com.fermapp.api.persistence.repository.WeatherAlertRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Weather alerts (weather.service.ts): Open-Meteo provides the hail codes, and OpenWeatherMap
 * takes priority for the other types when configured. The same alert is not repeated on the
 * same day for the same parcel.
 */
@Service
class WeatherService(
    private val openMeteo: OpenMeteoClient,
    private val openWeather: OpenWeatherClient,
    private val alerts: WeatherAlertRepository,
    private val realtime: RealtimeHub,
    private val notifications: NotificationService,
) {

    private class SourcedAlert(val alert: WeatherAlertDraft, val source: String)

    private fun collect(parcel: Parcel): List<SourcedAlert> {
        val byType = LinkedHashMap<String, SourcedAlert>()
        val forecast: Forecast? = try {
            openMeteo.getForecast(parcel.centerLat, parcel.centerLng)
        } catch (error: RuntimeException) {
            log.warn("Open-Meteo unavailable for alerts: {}", error.message)
            null
        }
        if (forecast != null) {
            for (alert in WeatherRules.evaluateWeatherAlerts(parcel.name, OpenMeteoClient.upcomingHours(forecast, Times.now()))) {
                byType[alert.type.name] = SourcedAlert(alert, "open-meteo")
            }
        }
        if (openWeather.isConfigured()) {
            val hours: List<ForecastHour>? = try {
                openWeather.getHours(parcel.centerLat, parcel.centerLng)
            } catch (error: RuntimeException) {
                log.warn("OpenWeatherMap unavailable for alerts: {}", error.message)
                null
            }
            if (hours != null) {
                for (alert in WeatherRules.evaluateWeatherAlerts(parcel.name, hours)) {
                    byType[alert.type.name] = SourcedAlert(alert, "openweathermap")
                }
            }
        }
        return byType.values.toList()
    }

    /** Saves the new alerts of the parcel, emits and notifies them, and returns the ones created now. */
    fun checkParcelAlerts(parcel: Parcel): List<AlertView> {
        val created = mutableListOf<AlertView>()
        for (sourced in collect(parcel)) {
            val alert = sourced.alert
            val dedupeKey = alert.type.name + ":" + Text.slice(alert.startsAt, 10)
            if (alerts.existsByParcelIdAndDedupeKey(parcel.id, dedupeKey)) {
                continue
            }
            var row = WeatherAlert()
            row.farmId = parcel.farmId
            row.parcelId = parcel.id
            row.type = alert.type
            row.severity = alert.severity
            row.title = alert.title
            row.message = alert.message
            row.startsAt = alert.startsAt
            row.dedupeKey = dedupeKey
            row.source = sourced.source
            try {
                row = alerts.saveAndFlush(row)
            } catch (error: DataIntegrityViolationException) {
                if (GlobalExceptionHandler.isUniqueViolation(error)) {
                    continue
                }
                throw error
            }
            val view = AlertView.of(row)
            created += view
            realtime.emitToFarm(parcel.farmId, RealtimeEvent.ALERT_NEW, view)
            notifications.notify(
                NotificationInput(
                    parcel.farmId, null, null, alert.title, alert.message,
                    mapOf("type" to "alert", "id" to row.id.toString(), "parcelId" to parcel.id.toString()),
                ),
            )
        }
        return created
    }

    private companion object {
        val log = LoggerFactory.getLogger(WeatherService::class.java)
    }
}
