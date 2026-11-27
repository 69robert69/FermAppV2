package com.fermapp.api.common.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Time helpers that reproduce the JavaScript Date behaviour the TypeScript server relied on.
 * Timestamps carry millisecond precision, like Date and the TIMESTAMP(3) columns.
 */
object Times {

    const val DAY_MS = 86_400_000L

    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** `new Date()`: the current instant, truncated to milliseconds. */
    @JvmStatic
    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    /**
     * `date.toISOString()`: always three fraction digits and a Z; null stays null.
     * Kotlin code holding a non-null instant can use [toIsoString] instead.
     */
    @JvmStatic
    fun iso(instant: Instant?): String? = instant?.let(ISO_MILLIS::format)

    /** `date.toISOString().slice(0, 10)`: the UTC calendar day. */
    @JvmStatic
    fun isoDay(instant: Instant?): String? = iso(instant)?.substring(0, 10)

    internal fun format(instant: Instant): String = ISO_MILLIS.format(instant)

    /** `new Date().getFullYear()`: the year in the server's local time zone. */
    @JvmStatic
    fun localYear(instant: Instant): Int = instant.atZone(ZoneId.systemDefault()).year

    @JvmStatic
    fun currentLocalYear(): Int = localYear(Instant.now())

    /** `getUTCFullYear()`. */
    @JvmStatic
    fun utcYear(instant: Instant): Int = instant.atZone(ZoneOffset.UTC).year

    /** `new Date(Date.UTC(year, month - 1, day, hour))`: out-of-range parts roll over, as in JavaScript. */
    @JvmStatic
    fun utc(year: Int, month: Int, day: Int, hour: Int): Instant =
        LocalDate.of(year, 1, 1).plusMonths(month - 1L).plusDays(day - 1L)
            .atStartOfDay(ZoneOffset.UTC).plusHours(hour.toLong()).toInstant()

    /** `new Date("YYYY-MM-DD")`: midnight UTC of that day. */
    @JvmStatic
    fun utcMidnight(isoDate: String): Instant = LocalDate.parse(isoDate.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant()

    @JvmStatic
    fun atUtcMidnight(date: LocalDate?): Instant? = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

    @JvmStatic
    fun utcDate(instant: Instant): LocalDate = instant.atZone(ZoneOffset.UTC).toLocalDate()

    @JvmStatic
    fun plusDays(instant: Instant, days: Double): Instant = instant.plusMillis(Math.round(days * DAY_MS))
}

/**
 * [Times.iso] for an instant known to be non-null. It is an extension, not an overload of
 * `iso`, so that a value of unknown nullability coming from Java keeps going through the
 * null-tolerant `Times.iso` instead of failing here.
 */
fun Instant.toIsoString(): String = Times.format(this)
