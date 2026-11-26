package ro.fermapp.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.hours

@Serializable
data class HourlyWeather(
    /** Local time at the parcel: YYYY-MM-DDTHH:mm. */
    val time: String,
    val temperatureC: Double,
    val humidityPct: Double,
    val precipitationMm: Double,
    val precipitationProbabilityPct: Double? = null,
    val windKmh: Double,
    /** WMO code, used for thunderstorms (95) and hail (96, 99). */
    val weatherCode: Int? = null,
    val gustKmh: Double? = null,
    /** Reference evapotranspiration (FAO-56) for the hour. */
    val et0Mm: Double? = null,
    val radiationWm2: Double? = null,
)

/** Forecast hours carry a WMO weather code; it is optional on [HourlyWeather]. */
typealias ForecastHour = HourlyWeather

/** The weather brief also reads gusts, ET₀ and radiation, all optional on [HourlyWeather]. */
typealias BriefHour = HourlyWeather

@Serializable
enum class FieldOperation(val label: String) {
    SPRAYING("spraying"),
    FERTILIZING("fertilizing"),
    SOWING("sowing"),
    HARVESTING("harvesting"),
}

private class OperationRule(
    val maxWindKmh: Double,
    val tempC: Range,
    val humidityPct: Range,
    val maxRainProbabilityPct: Double,
    val maxHourlyRainMm: Double,
    /** Maximum rain accepted in the preceding hours (wet ground or plants). */
    val maxRecentRainMm: Double,
    val recentHours: Int,
    val idealWindKmh: Double,
    val idealTempC: Double,
)

private val RULES = mapOf(
    FieldOperation.SPRAYING to OperationRule(15.0, Range(8.0, 25.0), Range(45.0, 95.0), 30.0, 0.0, 1.0, 3, 6.0, 16.0),
    FieldOperation.FERTILIZING to OperationRule(25.0, Range(2.0, 28.0), Range(30.0, 100.0), 60.0, 1.0, 8.0, 6, 8.0, 14.0),
    FieldOperation.SOWING to OperationRule(40.0, Range(5.0, 32.0), Range(25.0, 95.0), 40.0, 0.2, 6.0, 24, 10.0, 18.0),
    FieldOperation.HARVESTING to OperationRule(45.0, Range(10.0, 38.0), Range(15.0, 72.0), 25.0, 0.0, 1.0, 12, 12.0, 26.0),
)

@Serializable
data class WorkWindow(
    val operation: FieldOperation,
    val start: String,
    val end: String,
    val hours: Int,
    val score: Int,
    val label: String,
)

internal val DAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/** Day name of a YYYY-MM-DD... string. */
internal fun dayName(time: String): String = DAY_NAMES[LocalDate.parse(time.take(10)).dayOfWeek.isoDayNumber % 7]

/** Wall-clock time as written in the forecast, YYYY-MM-DDTHH:mm. */
internal fun parseLocalMinute(time: String): LocalDateTime = LocalDateTime.parse(time.take(16))

internal fun LocalDateTime.toMinuteString(): String = toString().take(16)

private fun addHour(time: String): String {
    val instant = parseLocalMinute(time).toInstant(TimeZone.UTC) + 1.hours
    return instant.toLocalDateTime(TimeZone.UTC).toMinuteString()
}

/** Intervals that cross midnight also show their end day. */
private fun windowLabel(start: String, end: String): String {
    val startClock = start.substring(11, 16)
    val endClock = end.substring(11, 16)
    val hoursLong = (parseLocalMinute(end).toInstant(TimeZone.UTC) - parseLocalMinute(start).toInstant(TimeZone.UTC)).inWholeMinutes / 60.0
    val sameDay = start.take(10) == end.take(10)
    if (sameDay || (endClock == "00:00" && hoursLong <= 24)) {
        return "${dayName(start)} $startClock-$endClock"
    }
    return "${dayName(start)} $startClock - ${dayName(end)} $endClock"
}

private fun hourScore(hour: HourlyWeather, rule: OperationRule): Double {
    val windPenalty = max(0.0, hour.windKmh - rule.idealWindKmh) / max(1.0, rule.maxWindKmh - rule.idealWindKmh)
    val span = max(rule.tempC.max - rule.idealTempC, rule.idealTempC - rule.tempC.min)
    val tempPenalty = abs(hour.temperatureC - rule.idealTempC) / span
    val rainPenalty = (hour.precipitationProbabilityPct ?: 0.0) / 100
    return max(0.0, 100 - windPenalty * 40 - tempPenalty * 35 - rainPenalty * 25)
}

private fun isSuitable(hours: List<HourlyWeather>, index: Int, rule: OperationRule): Boolean {
    val hour = hours[index]
    if (hour.windKmh > rule.maxWindKmh) return false
    if (hour.temperatureC < rule.tempC.min || hour.temperatureC > rule.tempC.max) return false
    if (hour.humidityPct < rule.humidityPct.min || hour.humidityPct > rule.humidityPct.max) return false
    if (hour.precipitationMm > rule.maxHourlyRainMm) return false
    if ((hour.precipitationProbabilityPct ?: 0.0) > rule.maxRainProbabilityPct) return false
    val recent = hours.subList(max(0, index - rule.recentHours), index).sumOf { it.precipitationMm }
    return recent <= rule.maxRecentRainMm
}

/**
 * Finds suitable continuous intervals, ordered from best.
 * Hours before [from] only count towards recent rain.
 */
fun findWorkWindows(
    operation: FieldOperation,
    hours: List<HourlyWeather>,
    minHours: Int = 2,
    from: String? = null,
): List<WorkWindow> {
    val rule = RULES.getValue(operation)
    val windows = mutableListOf<WorkWindow>()
    var startIndex = -1

    fun close(endIndex: Int) {
        val count = endIndex - startIndex
        if (startIndex < 0 || count < minHours) return
        val slice = hours.subList(startIndex, endIndex)
        val average = slice.sumOf { hourScore(it, rule) } / count
        val durationBonus = min(10, count)
        val first = slice.first()
        val end = addHour(slice.last().time)
        windows.add(
            WorkWindow(
                operation = operation,
                start = first.time,
                end = end,
                hours = count,
                score = min(100, jsRoundInt(average * 0.9 + durationBonus)),
                label = windowLabel(first.time, end),
            ),
        )
    }

    hours.forEachIndexed { index, item ->
        val suitable = (from == null || item.time >= from) && isSuitable(hours, index, rule)
        if (suitable && startIndex < 0) startIndex = index
        if (!suitable && startIndex >= 0) {
            close(index)
            startIndex = -1
        }
    }
    if (startIndex >= 0) close(hours.size)

    return windows.sortedWith(compareByDescending<WorkWindow> { it.score }.thenBy { it.start })
}

fun bestWindowMessage(operation: FieldOperation, windows: List<WorkWindow>): String {
    val best = windows.firstOrNull()
    return if (best != null) {
        "Best window for ${operation.label}: ${best.label}"
    } else {
        "No suitable window for ${operation.label} in the analysed period."
    }
}
