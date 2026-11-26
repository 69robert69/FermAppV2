package ro.fermapp.domain

import kotlinx.serialization.Serializable

@Serializable
enum class AlertType { RAIN, STORM, HAIL, FROST, HEAT, WIND_SPRAYING }

@Serializable
enum class AlertSeverity { INFO, WARNING, SEVERE }

@Serializable
data class WeatherAlertDraft(
    val type: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val startsAt: String,
)

private const val RAIN_THRESHOLD_MM = 0.5
private const val FROST_C = 0.0
private const val HEAT_C = 35.0

private fun inHours(n: Int) = if (n <= 1) "within the hour" else "in $n hours"

/** Generates at most one alert per type, for the first relevant moment in the horizon. */
fun evaluateWeatherAlerts(parcelName: String, hours: List<ForecastHour>, horizonHours: Int = 24): List<WeatherAlertDraft> {
    val window = hours.take(horizonHours)
    val alerts = mutableListOf<WeatherAlertDraft>()

    fun check(type: AlertType, severity: AlertSeverity, title: String, predicate: (ForecastHour) -> Boolean, message: (Int, ForecastHour) -> String) {
        val index = window.indexOfFirst(predicate)
        if (index >= 0) alerts.add(WeatherAlertDraft(type, severity, title, message(index, window[index]), window[index].time))
    }

    check(AlertType.HAIL, AlertSeverity.SEVERE, "Hail risk", { it.weatherCode == 96 || it.weatherCode == 99 }) { i, _ ->
        "Hailstorm expected ${inHours(i)} on parcel $parcelName."
    }
    check(AlertType.STORM, AlertSeverity.SEVERE, "Thunderstorm", { it.weatherCode == 95 }) { i, _ ->
        "Thunderstorm expected ${inHours(i)} on parcel $parcelName."
    }
    check(AlertType.RAIN, AlertSeverity.INFO, "Rain", { it.precipitationMm >= RAIN_THRESHOLD_MM }) { i, _ ->
        "Rain expected ${inHours(i)} on parcel $parcelName."
    }
    check(AlertType.FROST, AlertSeverity.WARNING, "Frost", { it.temperatureC <= FROST_C }) { i, h ->
        "Frost expected ${inHours(i)} on parcel $parcelName: ${jsNum(jsRound(h.temperatureC))} °C."
    }
    check(AlertType.HEAT, AlertSeverity.WARNING, "Extreme temperatures", { it.temperatureC >= HEAT_C }) { i, h ->
        "Heatwave expected ${inHours(i)} on parcel $parcelName: ${jsNum(jsRound(h.temperatureC))} °C."
    }

    val current = window.firstOrNull()
    if (current != null && current.windKmh > SPRAY_WIND_LIMIT_KMH) {
        alerts.add(
            WeatherAlertDraft(
                AlertType.WIND_SPRAYING,
                AlertSeverity.WARNING,
                "Strong wind",
                "Wind ${jsNum(jsRound(current.windKmh))} km/h. Herbicide spraying is not recommended.",
                current.time,
            ),
        )
    }
    return alerts
}
