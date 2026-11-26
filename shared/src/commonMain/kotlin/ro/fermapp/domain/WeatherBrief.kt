package ro.fermapp.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.Serializable

/*
 * The weather summary shown on the map: current conditions, a five-day outlook,
 * farming advice and alerts, all derived from an hourly forecast in local parcel time.
 */

@Serializable
enum class ConditionKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN, SNOW, SLEET, STORM, HAIL }

@Serializable
data class WeatherCondition(val kind: ConditionKind, val label: String)

/** WMO weather interpretation codes, as returned by Open-Meteo. */
fun weatherCondition(code: Int?): WeatherCondition = when (code) {
    0 -> WeatherCondition(ConditionKind.CLEAR, "Clear")
    1 -> WeatherCondition(ConditionKind.CLEAR, "Mainly clear")
    2 -> WeatherCondition(ConditionKind.PARTLY_CLOUDY, "Partly cloudy")
    3 -> WeatherCondition(ConditionKind.CLOUDY, "Overcast")
    45, 48 -> WeatherCondition(ConditionKind.FOG, "Fog")
    51, 53, 55 -> WeatherCondition(ConditionKind.DRIZZLE, "Drizzle")
    56, 57 -> WeatherCondition(ConditionKind.SLEET, "Freezing drizzle")
    61 -> WeatherCondition(ConditionKind.RAIN, "Light rain")
    63 -> WeatherCondition(ConditionKind.RAIN, "Rain")
    65 -> WeatherCondition(ConditionKind.HEAVY_RAIN, "Heavy rain")
    66, 67 -> WeatherCondition(ConditionKind.SLEET, "Freezing rain")
    71, 73, 75, 77 -> WeatherCondition(ConditionKind.SNOW, "Snow")
    80, 81 -> WeatherCondition(ConditionKind.RAIN, "Showers")
    82 -> WeatherCondition(ConditionKind.HEAVY_RAIN, "Violent showers")
    85, 86 -> WeatherCondition(ConditionKind.SNOW, "Snow showers")
    95 -> WeatherCondition(ConditionKind.STORM, "Thunderstorm")
    96, 99 -> WeatherCondition(ConditionKind.HAIL, "Thunderstorm with hail")
    else -> WeatherCondition(ConditionKind.CLOUDY, "Cloudy")
}

@Serializable
data class BriefDay(val date: String, val tMaxC: Double, val tMinC: Double, val precipitationMm: Double)

/** Measured conditions, which are more precise than the forecast for the current hour. */
@Serializable
data class BriefCurrent(
    val time: String,
    val temperatureC: Double,
    val humidityPct: Double,
    val windKmh: Double,
    val gustKmh: Double? = null,
    val weatherCode: Int? = null,
    val isDay: Boolean? = null,
)

@Serializable
data class DayOutlook(
    val date: String,
    /** Short weekday name, for example "Mon". */
    val weekday: String,
    val tMaxC: Double,
    val tMinC: Double,
    val precipitationMm: Double,
    val rainProbabilityPct: Double?,
    val condition: WeatherCondition,
)

@Serializable
enum class InsightTone { GOOD, INFO, CAUTION, WARNING }

@Serializable
enum class InsightCode { SPRAY_WIND, RAIN_SOON, SPRAY_GOOD, SPRAY_HOT, SPRAY_COLD, SPRAY_DRY_AIR, WET_SOIL, SOIL_DRYING, STABLE }

@Serializable
data class FarmInsight(val code: InsightCode, val tone: InsightTone, val message: String)

@Serializable
enum class FieldAlertType { STORM, FROST, WIND, HEAT, HEAVY_RAIN }

@Serializable
enum class FieldAlertSeverity { WARNING, SEVERE }

@Serializable
data class FieldAlert(
    val type: FieldAlertType,
    val severity: FieldAlertSeverity,
    val title: String,
    val message: String,
    val startsAt: String,
)

@Serializable
data class CurrentWeather(
    val time: String,
    val temperatureC: Double,
    val humidityPct: Double,
    val windKmh: Double,
    val gustKmh: Double?,
    val precipitationProbabilityPct: Double?,
    val condition: WeatherCondition,
    val isDay: Boolean,
)

@Serializable
data class TodaySummary(val tMinC: Double, val tMaxC: Double, val rainProbabilityPct: Double?)

@Serializable
data class WeatherBrief(
    val current: CurrentWeather,
    val today: TodaySummary?,
    val days: List<DayOutlook>,
    val insights: List<FarmInsight>,
    val alerts: List<FieldAlert>,
)

data class WeatherBriefInput(
    /** Hourly forecast in local time; may include past hours. */
    val hours: List<BriefHour>,
    val days: List<BriefDay>,
    val current: BriefCurrent? = null,
    /** The current local hour, YYYY-MM-DDTHH:00. */
    val now: String,
    /** Number of days in the outlook, starting tomorrow. */
    val outlookDays: Int = 5,
)

// Thresholds. Spraying limits follow the work-window rules (Windows.kt).
private const val SPRAY_MAX_RAIN_PCT = 30.0
private const val SPRAY_TEMP_MIN_C = 8.0
private const val SPRAY_TEMP_MAX_C = 25.0
private const val SPRAY_MIN_HUMIDITY_PCT = 45.0
private const val RAIN_HOUR_MM = 0.5
private const val RAIN_LIKELY_PCT = 60.0
private const val WET_SOIL_MM = 10.0
private const val DRYING_MAX_RAIN_MM = 1.0
private const val DRYING_ET0_MM = 2.5
private const val FROST_C = 0.0
private const val HARD_FROST_C = -2.0
private const val HEAT_C = 32.0
private const val EXTREME_HEAT_C = 35.0
private const val WIND_SUSTAINED_KMH = 40.0
private const val WIND_GUST_KMH = 60.0
private const val WIND_GUST_SEVERE_KMH = 80.0
private const val HEAVY_RAIN_HOUR_MM = 7.6
private const val HEAVY_RAIN_DAY_MM = 20.0
private const val HEAVY_RAIN_SEVERE_MM = 40.0
private const val MAX_INSIGHTS = 3

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

private fun weekdayOf(date: String) = WEEKDAYS[LocalDate.parse(date.take(10)).dayOfWeek.isoDayNumber % 7]

private fun nextDate(date: String) = LocalDate.fromEpochDays(LocalDate.parse(date.take(10)).toEpochDays() + 1).toString()

/** "now", "today at 15:00", "tomorrow at 03:00" or "on Thu". */
fun relativeTime(time: String, now: String): String {
    if (time.take(13) <= now.take(13)) return "now"
    val date = time.take(10)
    val clock = time.substring(11, 16)
    val today = now.take(10)
    if (date == today) return "today at $clock"
    if (date == nextDate(today)) return "tomorrow at $clock"
    return "on ${weekdayOf(date)}"
}

/** Codes are ordered by severity, so the day's icon shows its worst daytime weather. */
private fun isDaytime(time: String): Boolean {
    val hour = time.substring(11, 13).toInt()
    return hour in 6..21
}

private fun dailyCode(hours: List<BriefHour>): Int? {
    val daytime = hours.filter { isDaytime(it.time) && it.weatherCode != null }
    val pool = daytime.ifEmpty { hours.filter { it.weatherCode != null } }
    if (pool.isEmpty()) return null
    // A single stormy hour should not paint the whole day: storms need two hours unless nothing else is wet.
    val codes = pool.map { it.weatherCode!! }
    val worst = codes.max()
    if (worst >= 95 && codes.count { it >= 95 } < 2) {
        val rest = codes.filter { it < 95 }
        return if (rest.isNotEmpty()) rest.max() else worst
    }
    return worst
}

private fun round(value: Double) = jsNum(jsRound(value))

private fun buildInsights(upcoming: List<BriefHour>, past24: List<BriefHour>, current: CurrentWeather): List<FarmInsight> {
    val insights = mutableListOf<FarmInsight>()
    val next6 = upcoming.take(6)
    val next24 = upcoming.take(24)
    val next48 = upcoming.take(48)

    val windy = current.windKmh > SPRAY_WIND_LIMIT_KMH
    if (windy) {
        insights.add(FarmInsight(InsightCode.SPRAY_WIND, InsightTone.WARNING, "Strong winds detected (${round(current.windKmh)} km/h). Spraying not recommended."))
    }

    val rainIndex = next24.indexOfFirst { it.precipitationMm >= RAIN_HOUR_MM || (it.precipitationProbabilityPct ?: 0.0) >= RAIN_LIKELY_PCT }
    if (rainIndex >= 0) {
        val rainTotal = next24.sumOf { it.precipitationMm }
        val amount = if (rainTotal >= 1) " (about ${round(rainTotal)} mm)" else ""
        insights.add(
            FarmInsight(
                InsightCode.RAIN_SOON,
                InsightTone.INFO,
                when {
                    rainIndex <= 1 -> "Rain expected within the hour$amount."
                    rainIndex < 6 -> "Rain expected in the next $rainIndex hours$amount. Postpone spraying and fertilizer spreading."
                    else -> "Rain expected within 24 hours$amount."
                },
            ),
        )
    }

    val rainRiskSoon = next6.any { it.precipitationMm > 0 || (it.precipitationProbabilityPct ?: 0.0) > SPRAY_MAX_RAIN_PCT }
    if (!windy && !rainRiskSoon) {
        insights.add(
            when {
                current.temperatureC > SPRAY_TEMP_MAX_C ->
                    FarmInsight(InsightCode.SPRAY_HOT, InsightTone.CAUTION, "Too warm for effective spraying now. Prefer early morning or evening.")
                current.temperatureC < SPRAY_TEMP_MIN_C ->
                    FarmInsight(InsightCode.SPRAY_COLD, InsightTone.CAUTION, "Too cold for most crop protection products to work well.")
                current.humidityPct < SPRAY_MIN_HUMIDITY_PCT ->
                    FarmInsight(InsightCode.SPRAY_DRY_AIR, InsightTone.CAUTION, "Dry air: high drift and evaporation risk when spraying.")
                else -> FarmInsight(InsightCode.SPRAY_GOOD, InsightTone.GOOD, "Low rain probability. Good conditions for spraying.")
            },
        )
    }

    val recentRain = past24.sumOf { it.precipitationMm }
    val rainAhead = next48.sumOf { it.precipitationMm }
    val et0 = if (next24.any { it.et0Mm != null }) next24.sumOf { it.et0Mm ?: 0.0 } else null
    val meanHumidity = if (next24.isNotEmpty()) next24.sumOf { it.humidityPct } / next24.size else 100.0
    val meanTemp = if (next24.isNotEmpty()) next24.sumOf { it.temperatureC } / next24.size else 0.0
    val drying = rainAhead < DRYING_MAX_RAIN_MM && (if (et0 != null) et0 >= DRYING_ET0_MM else meanHumidity < 65 && meanTemp >= 12)

    if (recentRain >= WET_SOIL_MM && !drying) {
        insights.add(FarmInsight(InsightCode.WET_SOIL, InsightTone.CAUTION, "${round(recentRain)} mm fell in the last 24 hours. Soils are likely wet; delay heavy machinery."))
    } else if (drying) {
        insights.add(FarmInsight(InsightCode.SOIL_DRYING, InsightTone.GOOD, "Good soil drying conditions."))
    }

    if (insights.isEmpty()) insights.add(FarmInsight(InsightCode.STABLE, InsightTone.INFO, "Stable conditions. No weather constraints for field work."))
    return insights.take(MAX_INSIGHTS)
}

private fun buildAlerts(upcoming: List<BriefHour>, now: String): List<FieldAlert> {
    val alerts = mutableListOf<FieldAlert>()
    val next24 = upcoming.take(24)
    val next48 = upcoming.take(48)
    val next72 = upcoming.take(72)
    fun whenOf(hour: BriefHour) = relativeTime(hour.time, now)

    val storm = next48.firstOrNull { it.weatherCode != null && it.weatherCode >= 95 }
    if (storm != null) {
        val hail = next48.any { it.weatherCode == 96 || it.weatherCode == 99 }
        alerts.add(
            FieldAlert(
                FieldAlertType.STORM, FieldAlertSeverity.SEVERE,
                if (hail) "Storm and hail" else "Storm warning",
                "${if (hail) "Thunderstorm with hail" else "Thunderstorm"} expected ${whenOf(storm)}.",
                storm.time,
            ),
        )
    }

    // reduce keeps the first of equal values, like minByOrNull.
    val coldest = next48.minByOrNull { it.temperatureC }
    if (coldest != null && coldest.temperatureC <= FROST_C) {
        val first = next48.first { it.temperatureC <= FROST_C }
        alerts.add(
            FieldAlert(
                FieldAlertType.FROST,
                if (coldest.temperatureC <= HARD_FROST_C) FieldAlertSeverity.SEVERE else FieldAlertSeverity.WARNING,
                "Frost warning",
                "Frost ${whenOf(first)}, down to ${round(coldest.temperatureC)} °C.",
                first.time,
            ),
        )
    }

    val windy = next24.firstOrNull { it.windKmh >= WIND_SUSTAINED_KMH || (it.gustKmh ?: 0.0) >= WIND_GUST_KMH }
    if (windy != null) {
        val peakGust = next24.maxOfOrNull { it.gustKmh ?: it.windKmh } ?: windy.windKmh
        alerts.add(
            FieldAlert(
                FieldAlertType.WIND,
                if (peakGust >= WIND_GUST_SEVERE_KMH) FieldAlertSeverity.SEVERE else FieldAlertSeverity.WARNING,
                "Strong wind",
                "Gusts up to ${round(peakGust)} km/h ${whenOf(windy)}.",
                windy.time,
            ),
        )
    }

    val hottest = next72.maxByOrNull { it.temperatureC }
    if (hottest != null && hottest.temperatureC >= HEAT_C) {
        val first = next72.first { it.temperatureC >= HEAT_C }
        alerts.add(
            FieldAlert(
                FieldAlertType.HEAT,
                if (hottest.temperatureC >= EXTREME_HEAT_C) FieldAlertSeverity.SEVERE else FieldAlertSeverity.WARNING,
                "Heat stress",
                "Up to ${round(hottest.temperatureC)} °C ${whenOf(first)}. Risk of crop heat stress.",
                first.time,
            ),
        )
    }

    // The heaviest rolling 24-hour total in the next two days, or a single intense hour.
    var peakTotal = 0.0
    var peakStart: BriefHour? = null
    for (i in next48.indices) {
        val total = next48.subList(i, minOf(i + 24, next48.size)).sumOf { it.precipitationMm }
        if (total > peakTotal) {
            peakTotal = total
            peakStart = next48.drop(i).firstOrNull { it.precipitationMm > 0 } ?: next48[i]
        }
    }
    val intense = next48.firstOrNull { it.precipitationMm >= HEAVY_RAIN_HOUR_MM }
    if (peakTotal >= HEAVY_RAIN_DAY_MM || intense != null) {
        val start = if (intense != null && (peakStart == null || intense.time < peakStart.time)) intense else peakStart!!
        alerts.add(
            FieldAlert(
                FieldAlertType.HEAVY_RAIN,
                if (peakTotal >= HEAVY_RAIN_SEVERE_MM) FieldAlertSeverity.SEVERE else FieldAlertSeverity.WARNING,
                "Heavy rain",
                "About ${round(peakTotal)} mm expected ${whenOf(start)}. Risk of waterlogging and erosion.",
                start.time,
            ),
        )
    }

    return alerts.sortedWith(
        compareBy<FieldAlert> { if (it.severity == FieldAlertSeverity.SEVERE) 0 else 1 }.thenBy { it.startsAt },
    )
}

fun buildWeatherBrief(input: WeatherBriefInput): WeatherBrief? {
    val nowHour = input.now.take(13)
    val upcoming = input.hours.filter { it.time.take(13) >= nowHour }
    val first = upcoming.firstOrNull() ?: return null
    val past24 = input.hours.filter { it.time.take(13) < nowHour }.takeLast(24)
    val live = input.current
    val today = input.now.take(10)

    val hoursByDate = input.hours.groupBy { it.time.take(10) }
    fun rainChance(date: String) = hoursByDate[date]?.maxOfOrNull { it.precipitationProbabilityPct ?: 0.0 }

    val isDay = live?.isDay ?: first.radiationWm2?.let { it > 0 } ?: isDaytime(first.time)
    val current = CurrentWeather(
        time = live?.time ?: first.time,
        temperatureC = live?.temperatureC ?: first.temperatureC,
        humidityPct = live?.humidityPct ?: first.humidityPct,
        windKmh = live?.windKmh ?: first.windKmh,
        gustKmh = live?.gustKmh ?: first.gustKmh,
        precipitationProbabilityPct = first.precipitationProbabilityPct,
        condition = weatherCondition(live?.weatherCode ?: first.weatherCode),
        isDay = isDay,
    )

    val todayRow = input.days.firstOrNull { it.date == today }
    val todayHours = hoursByDate[today] ?: emptyList()
    val todaySummary = when {
        todayRow != null -> TodaySummary(todayRow.tMinC, todayRow.tMaxC, rainChance(today))
        todayHours.isNotEmpty() -> TodaySummary(todayHours.minOf { it.temperatureC }, todayHours.maxOf { it.temperatureC }, rainChance(today))
        else -> null
    }

    val days = input.days
        .filter { it.date > today }
        .take(input.outlookDays)
        .map { day ->
            DayOutlook(
                date = day.date,
                weekday = weekdayOf(day.date),
                tMaxC = day.tMaxC,
                tMinC = day.tMinC,
                precipitationMm = day.precipitationMm,
                rainProbabilityPct = rainChance(day.date),
                condition = weatherCondition(dailyCode(hoursByDate[day.date] ?: emptyList())),
            )
        }

    // Measured conditions replace the first forecast hour, so advice matches what the farmer sees outside.
    val adviceHours = if (live != null) {
        listOf(first.copy(temperatureC = current.temperatureC, humidityPct = current.humidityPct, windKmh = current.windKmh)) + upcoming.drop(1)
    } else {
        upcoming
    }

    return WeatherBrief(
        current = current,
        today = todaySummary,
        days = days,
        insights = buildInsights(adviceHours, past24, current),
        alerts = buildAlerts(adviceHours, input.now),
    )
}
