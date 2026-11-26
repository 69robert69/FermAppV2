package ro.fermapp.app.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import ro.fermapp.domain.copilot.COPILOT_TASK_TYPES
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.parseJsDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Instant

/** The formatting helpers of lib/format.ts, in British English as the original. */

fun parseNumber(value: String): Double? = value.replace(',', '.').trim().toDoubleOrNull()

fun isPositive(value: String): Boolean = (parseNumber(value) ?: 0.0) > 0 && value.isNotBlank()

/** 1234.5 -> "1,234.5", at most [digits] decimals, as toLocaleString('en-GB'). */
fun number(value: Double, digits: Int = 1): String {
    if (!value.isFinite()) return "-"
    val factor = 10.0.pow(digits)
    val rounded = jsRound(abs(value) * factor) / factor
    val integer = floor(rounded).toLong()
    var fraction = ((rounded - integer) * factor).let { jsRound(it).toLong() }.toString().padStart(digits, '0').trimEnd('0')
    if (digits == 0) fraction = ""
    val grouped = integer.toString().reversed().chunked(3).joinToString(",").reversed()
    val sign = if (value < 0 && rounded != 0.0) "-" else ""
    return sign + grouped + if (fraction.isNotEmpty()) ".$fraction" else ""
}

fun ha(value: Double) = "${number(value, 2)} ha"

fun duration(totalSeconds: Double): String {
    val seconds = max(0L, jsRound(totalSeconds).toLong())
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

fun hoursMinutes(totalSeconds: Double): String {
    val minutes = max(0L, jsRound(totalSeconds / 60).toLong())
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

private val MONTHS_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTHS_LONG = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

val localZone: TimeZone get() = TimeZone.currentSystemDefault()

fun toLocal(iso: String): LocalDateTime = parseJsDate(iso).toLocalDateTime(localZone)

/** "14:05" in local time. */
fun clock(iso: String): String {
    val t = toLocal(iso)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** "12 Jun". */
fun shortDate(iso: String): String {
    val t = toLocal(iso)
    return "${t.day} ${MONTHS_SHORT[t.month.ordinal]}"
}

/** "12 June 2026". */
fun fullDate(iso: String): String {
    val t = toLocal(iso)
    return "${t.day} ${MONTHS_LONG[t.month.ordinal]} ${t.year}"
}

/** Accepts a date in DD.MM.YYYY format and returns null if it is not valid. */
fun parseRoDate(value: String): Instant? {
    val match = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").find(value.trim()) ?: return null
    val (d, m, y) = match.destructured
    val date = runCatching { LocalDate(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() ?: return null
    return LocalDateTime(date.year, date.month, date.day, 12, 0).toInstant(localZone)
}

fun toRoDate(instant: Instant): String {
    val t = instant.toLocalDateTime(localZone)
    return "${t.day.toString().padStart(2, '0')}.${(t.month.ordinal + 1).toString().padStart(2, '0')}.${t.year}"
}

/** Today's local date as YYYY-MM-DD. */
fun todayIso(): String = kotlin.time.Clock.System.now().toLocalDateTime(localZone).date.toString()

fun startOfToday(): Instant = kotlin.time.Clock.System.now().toLocalDateTime(localZone).date.atStartOfDayIn(localZone)

val WORK_TYPES: Map<String, String> = linkedMapOf(
    "PLOWING" to "Ploughing",
    "DISCING" to "Discing",
    "SUBSOILING" to "Subsoiling",
    "SOWING" to "Sowing",
    "FERTILIZING" to "Fertilizing",
    "SPRAYING" to "Spraying",
    "HARVESTING" to "Harvesting",
)

val MACHINE_TYPES: Map<String, String> = linkedMapOf(
    "TRACTOR" to "Tractor",
    "COMBINE" to "Combine",
    "SPRAYER" to "Self-propelled sprayer",
    "SEEDER" to "Seed drill",
    "DISC" to "Disc",
    "CULTIVATOR" to "Cultivator",
    "SUBSOILER" to "Subsoiler",
    "TRAILER" to "Trailer",
)

val IRRIGATION_TYPES: Map<String, String> = linkedMapOf("REEL" to "Hose reel", "PIVOT" to "Pivot", "LINEAR" to "Linear")

/** Task types the copilot plans beyond the original list; manual tasks can use them too. */
val TASK_TYPES: Map<String, String> = linkedMapOf<String, String>().apply {
    COPILOT_TASK_TYPES.forEach { (code, item) -> put(code, item.label) }
    putAll(
        mapOf(
            "FERTILIZATION" to "Fertilization",
            "HERBICIDE" to "Herbicide application",
            "FUNGICIDE" to "Fungicide application",
            "INSECTICIDE" to "Insecticide application",
            "PEST_CHECK" to "Pest check",
            "DISEASE_CHECK" to "Disease check",
            "MOISTURE_CHECK" to "Moisture check",
            "LEAF_ANALYSIS" to "Leaf analysis",
            "HARVEST" to "Harvest",
            "SOIL_PREP" to "Seedbed preparation",
            "SEED_PURCHASE" to "Seed purchase",
            "SOWING" to "Sowing",
            "EMERGENCE_CHECK" to "Emergence check",
            "HARVEST_PREP" to "Harvest preparation",
        ),
    )
}

val PRIORITIES: Map<String, String> = linkedMapOf("LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High", "URGENT" to "Urgent")

val ROLE_LABELS: Map<String, String> = mapOf(
    "ADMIN" to "Administrator",
    "FARM_MANAGER" to "Farm Manager",
    "OPERATOR" to "Machine Operator",
    "AGRONOMIST" to "Agronomist Consultant",
)
