package ro.fermapp.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/*
 * The domain was first written in TypeScript and its texts and scores are compared with that version.
 * These helpers reproduce the JavaScript number behaviour that Kotlin does differently.
 */

/** JavaScript Math.round: halves round towards +infinity (-2.5 -> -2). */
fun jsRound(value: Double): Double = if (value.isFinite()) floor(value + 0.5) else value

/** Rounds to [digits] decimals the way `Math.round(x * 10^d) / 10^d` does. */
fun jsRound(value: Double, digits: Int): Double {
    val factor = 10.0.pow(digits)
    return jsRound(value * factor) / factor
}

/** JavaScript Math.round, as an Int. */
fun jsRoundInt(value: Double): Int = jsRound(value).toInt()

/** How JavaScript writes a number in a template string: 75, 2.5, -0.25. */
fun jsNum(value: Double): String = when {
    value.isNaN() -> "NaN"
    value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
    value == floor(value) && abs(value) < 1e21 -> value.toLong().toString()
    else -> value.toString()
}

fun jsNum(value: Int): String = value.toString()

/** JavaScript Number.prototype.toFixed. */
fun Double.toFixed(digits: Int): String {
    if (!isFinite()) return jsNum(this)
    val negative = this < 0
    val factor = 10.0.pow(digits)
    val scaled = jsRound(abs(this) * factor).toLong()
    val sign = if (negative && scaled != 0L) "-" else ""
    if (digits == 0) return sign + scaled
    val text = scaled.toString().padStart(digits + 1, '0')
    return sign + text.dropLast(digits) + "." + text.takeLast(digits)
}

/** JavaScript Date.prototype.toISOString: always milliseconds and a Z, e.g. 2026-11-23T14:57:36.980Z. */
fun Instant.toJsIsoString(): String {
    val dt = toLocalDateTime(TimeZone.UTC)
    val base = dt.toString().take(19).let { if (it.length == 16) "$it:00" else it }
    val millis = (dt.nanosecond / 1_000_000).toString().padStart(3, '0')
    return "$base.${millis}Z"
}

/** JavaScript `new Date(text)` for an ISO date or date-time string; plain dates are UTC midnight. */
fun parseJsDate(text: String): Instant {
    if (text.length == 10) return Instant.parse("${text}T00:00:00Z")
    val hasZone = text.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(text.drop(10))
    return Instant.parse(if (hasZone) text else "${text}Z")
}

internal const val DAY_MS = 86_400_000L

/**
 * `new Date(date.getTime() + ms)` with a fractional [ms]: JavaScript adds in floating point,
 * then truncates to whole milliseconds. Truncating first can land one millisecond early.
 */
fun Instant.plusJsMillis(ms: Double): Instant =
    Instant.fromEpochMilliseconds((toEpochMilliseconds().toDouble() + ms).toLong())

internal fun Double.clamp(min: Double, max: Double): Double = maxOf(min, minOf(max, this))
