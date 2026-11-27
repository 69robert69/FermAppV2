package com.fermapp.api.domain.weather

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fermapp.api.domain.model.AlertSeverity
import com.fermapp.api.domain.model.AlertType
import com.fermapp.api.domain.sumOfDoubles
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.EnumMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Work windows (windows.ts) and weather alerts (alerts.ts). */
object WeatherRules {

    /**
     * One forecast hour. [time] is local time at the parcel: YYYY-MM-DDTHH:mm.
     * [weatherCode] is the WMO code, used for thunderstorms (95) and hail (96, 99).
     * The methods keep record-style names because Java records implement this interface.
     */
    interface HourlyWeather {
        fun time(): String

        fun temperatureC(): Double

        fun humidityPct(): Double

        fun precipitationMm(): Double

        fun precipitationProbabilityPct(): Double?

        fun windKmh(): Double

        fun weatherCode(): Int?
    }

    /**
     * The basic forecast hour, as produced by OpenWeatherMap. The properties are private so
     * that the interface methods above are its only accessors, as with the former Java record.
     */
    @JsonPropertyOrder("time", "temperatureC", "humidityPct", "precipitationMm", "precipitationProbabilityPct", "windKmh", "weatherCode")
    data class ForecastHour(
        private val time: String,
        private val temperatureC: Double,
        private val humidityPct: Double,
        private val precipitationMm: Double,
        private val precipitationProbabilityPct: Double?,
        private val windKmh: Double,
        private val weatherCode: Int?,
    ) : HourlyWeather {
        @JsonProperty("time")
        override fun time() = time

        @JsonProperty("temperatureC")
        override fun temperatureC() = temperatureC

        @JsonProperty("humidityPct")
        override fun humidityPct() = humidityPct

        @JsonProperty("precipitationMm")
        override fun precipitationMm() = precipitationMm

        @JsonProperty("precipitationProbabilityPct")
        override fun precipitationProbabilityPct() = precipitationProbabilityPct

        @JsonProperty("windKmh")
        override fun windKmh() = windKmh

        @JsonProperty("weatherCode")
        override fun weatherCode() = weatherCode
    }

    enum class FieldOperation { SPRAYING, FERTILIZING, SOWING, HARVESTING }

    @JvmField
    val OPERATION_LABELS: Map<FieldOperation, String> = EnumMap(
        mapOf(
            FieldOperation.SPRAYING to "spraying",
            FieldOperation.FERTILIZING to "fertilizing",
            FieldOperation.SOWING to "sowing",
            FieldOperation.HARVESTING to "harvesting",
        ),
    )

    private class OperationRule(
        val maxWindKmh: Double,
        val tempMin: Double,
        val tempMax: Double,
        val humidityMin: Double,
        val humidityMax: Double,
        val maxRainProbabilityPct: Double,
        val maxHourlyRainMm: Double,
        val maxRecentRainMm: Double,
        val recentHours: Int,
        val idealWindKmh: Double,
        val idealTempC: Double,
    )

    private val RULES: Map<FieldOperation, OperationRule> = EnumMap(
        mapOf(
            FieldOperation.SPRAYING to OperationRule(15.0, 8.0, 25.0, 45.0, 95.0, 30.0, 0.0, 1.0, 3, 6.0, 16.0),
            FieldOperation.FERTILIZING to OperationRule(25.0, 2.0, 28.0, 30.0, 100.0, 60.0, 1.0, 8.0, 6, 8.0, 14.0),
            FieldOperation.SOWING to OperationRule(40.0, 5.0, 32.0, 25.0, 95.0, 40.0, 0.2, 6.0, 24, 10.0, 18.0),
            FieldOperation.HARVESTING to OperationRule(45.0, 10.0, 38.0, 15.0, 72.0, 25.0, 0.0, 1.0, 12, 12.0, 26.0),
        ),
    )

    @JvmRecord
    data class WorkWindow(val operation: FieldOperation, val start: String, val end: String, val hours: Int, val score: Int, val label: String)

    /** A drafted alert, before it is saved. */
    @JvmRecord
    @JsonInclude(JsonInclude.Include.ALWAYS)
    data class WeatherAlertDraft(val type: AlertType, val severity: AlertSeverity, val title: String, val message: String, val startsAt: String)

    private const val RAIN_THRESHOLD_MM = 0.5
    private const val SPRAY_WIND_LIMIT_KMH = 15.0
    private const val FROST_C = 0.0
    private const val HEAT_C = 35.0

    private fun dayName(time: String): String =
        LocalDate.parse(time.substring(0, 10)).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun parseLocal(time: String): LocalDateTime = LocalDateTime.parse(if (time.length > 16) time.substring(0, 16) else time)

    private fun addHour(time: String): String = parseLocal(time).plusHours(1).toString().substring(0, 16)

    /** Intervals that cross midnight also show their end day. */
    private fun windowLabel(start: String, end: String): String {
        val startClock = start.substring(11, 16)
        val endClock = end.substring(11, 16)
        val startAt = parseLocal(start).toInstant(ZoneOffset.UTC)
        val endAt = parseLocal(end).toInstant(ZoneOffset.UTC)
        val hoursLong = (endAt.toEpochMilli() - startAt.toEpochMilli()) / 3_600_000.0
        val sameDay = start.substring(0, 10) == end.substring(0, 10)
        if (sameDay || (endClock == "00:00" && hoursLong <= 24)) {
            return "${dayName(start)} $startClock-$endClock"
        }
        return "${dayName(start)} $startClock - ${dayName(end)} $endClock"
    }

    private fun probability(hour: HourlyWeather): Double = hour.precipitationProbabilityPct() ?: 0.0

    private fun hourScore(hour: HourlyWeather, rule: OperationRule): Double {
        val windPenalty = max(0.0, hour.windKmh() - rule.idealWindKmh) / max(1.0, rule.maxWindKmh - rule.idealWindKmh)
        val span = max(rule.tempMax - rule.idealTempC, rule.idealTempC - rule.tempMin)
        val tempPenalty = abs(hour.temperatureC() - rule.idealTempC) / span
        val rainPenalty = probability(hour) / 100
        return max(0.0, 100 - windPenalty * 40 - tempPenalty * 35 - rainPenalty * 25)
    }

    private fun isSuitable(hours: List<HourlyWeather>, index: Int, rule: OperationRule): Boolean {
        val hour = hours[index]
        if (hour.windKmh() > rule.maxWindKmh) return false
        if (hour.temperatureC() < rule.tempMin || hour.temperatureC() > rule.tempMax) return false
        if (hour.humidityPct() < rule.humidityMin || hour.humidityPct() > rule.humidityMax) return false
        if (hour.precipitationMm() > rule.maxHourlyRainMm) return false
        if (probability(hour) > rule.maxRainProbabilityPct) return false
        var recent = 0.0
        for (i in max(0, index - rule.recentHours) until index) {
            recent += hours[i].precipitationMm()
        }
        return recent <= rule.maxRecentRainMm
    }

    /**
     * Finds suitable continuous intervals, ordered from best.
     * Hours before [from] only count towards recent rain.
     */
    @JvmStatic
    fun findWorkWindows(operation: FieldOperation, hours: List<HourlyWeather>, minHours: Int, from: String?): List<WorkWindow> {
        val rule = RULES.getValue(operation)
        val windows = mutableListOf<WorkWindow>()
        var startIndex = -1
        for (index in 0..hours.size) {
            val suitable = index < hours.size &&
                (from == null || hours[index].time() >= from) &&
                isSuitable(hours, index, rule)
            if (suitable && startIndex < 0) {
                startIndex = index
            }
            if (!suitable && startIndex >= 0) {
                val count = index - startIndex
                if (count >= minHours) {
                    val slice = hours.subList(startIndex, index)
                    val average = slice.sumOfDoubles { hourScore(it, rule) } / count
                    val durationBonus = min(10, count)
                    val start = slice[0].time()
                    val end = addHour(slice[count - 1].time())
                    val score = min(100L, Math.round(average * 0.9 + durationBonus)).toInt()
                    windows += WorkWindow(operation, start, end, count, score, windowLabel(start, end))
                }
                startIndex = -1
            }
        }
        windows.sortWith(compareByDescending<WorkWindow> { it.score }.thenBy { it.start })
        return windows
    }

    @JvmStatic
    fun bestWindowMessage(operation: FieldOperation, windows: List<WorkWindow>): String {
        val name = OPERATION_LABELS.getValue(operation)
        return if (windows.isEmpty()) {
            "No suitable window for $name in the analysed period."
        } else {
            "Best window for $name: ${windows[0].label}"
        }
    }

    private fun inHours(n: Int) = if (n <= 1) "within the hour" else "in $n hours"

    private fun code(hour: HourlyWeather, value: Int) = hour.weatherCode() == value

    /** Generates at most one alert per type, for the first relevant moment in the next [horizonHours] hours. */
    @JvmStatic
    @JvmOverloads
    fun evaluateWeatherAlerts(parcelName: String, hours: List<HourlyWeather>, horizonHours: Int = 24): List<WeatherAlertDraft> {
        val window = hours.subList(0, min(horizonHours, hours.size))
        val alerts = mutableListOf<WeatherAlertDraft>()

        val hail = window.indexOfFirst { code(it, 96) || code(it, 99) }
        if (hail >= 0) {
            alerts += WeatherAlertDraft(
                AlertType.HAIL, AlertSeverity.SEVERE, "Hail risk",
                "Hailstorm expected ${inHours(hail)} on parcel $parcelName.", window[hail].time(),
            )
        }
        val storm = window.indexOfFirst { code(it, 95) }
        if (storm >= 0) {
            alerts += WeatherAlertDraft(
                AlertType.STORM, AlertSeverity.SEVERE, "Thunderstorm",
                "Thunderstorm expected ${inHours(storm)} on parcel $parcelName.", window[storm].time(),
            )
        }
        val rain = window.indexOfFirst { it.precipitationMm() >= RAIN_THRESHOLD_MM }
        if (rain >= 0) {
            alerts += WeatherAlertDraft(
                AlertType.RAIN, AlertSeverity.INFO, "Rain",
                "Rain expected ${inHours(rain)} on parcel $parcelName.", window[rain].time(),
            )
        }
        val frost = window.indexOfFirst { it.temperatureC() <= FROST_C }
        if (frost >= 0) {
            alerts += WeatherAlertDraft(
                AlertType.FROST, AlertSeverity.WARNING, "Frost",
                "Frost expected ${inHours(frost)} on parcel $parcelName: ${Math.round(window[frost].temperatureC())} °C.",
                window[frost].time(),
            )
        }
        val heat = window.indexOfFirst { it.temperatureC() >= HEAT_C }
        if (heat >= 0) {
            alerts += WeatherAlertDraft(
                AlertType.HEAT, AlertSeverity.WARNING, "Extreme temperatures",
                "Heatwave expected ${inHours(heat)} on parcel $parcelName: ${Math.round(window[heat].temperatureC())} °C.",
                window[heat].time(),
            )
        }
        val current = window.firstOrNull()
        if (current != null && current.windKmh() > SPRAY_WIND_LIMIT_KMH) {
            alerts += WeatherAlertDraft(
                AlertType.WIND_SPRAYING, AlertSeverity.WARNING, "Strong wind",
                "Wind ${Math.round(current.windKmh())} km/h. Herbicide spraying is not recommended.", current.time(),
            )
        }
        return alerts
    }
}
