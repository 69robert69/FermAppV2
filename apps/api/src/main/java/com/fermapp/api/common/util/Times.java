package com.fermapp.api.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Time helpers that reproduce the JavaScript Date behaviour the TypeScript server relied on.
 * Timestamps carry millisecond precision, like Date and the TIMESTAMP(3) columns.
 */
public final class Times {

    public static final long DAY_MS = 86_400_000L;
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Times() {
    }

    /** {@code new Date()}: the current instant, truncated to milliseconds. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** {@code date.toISOString()}: always three fraction digits and a Z. */
    public static String iso(Instant instant) {
        return instant == null ? null : ISO_MILLIS.format(instant);
    }

    /** {@code date.toISOString().slice(0, 10)}: the UTC calendar day. */
    public static String isoDay(Instant instant) {
        return instant == null ? null : iso(instant).substring(0, 10);
    }

    /** {@code new Date().getFullYear()}: the year in the server's local time zone. */
    public static int localYear(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).getYear();
    }

    public static int currentLocalYear() {
        return localYear(Instant.now());
    }

    /** {@code getUTCFullYear()}. */
    public static int utcYear(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).getYear();
    }

    /** {@code new Date(Date.UTC(year, month - 1, day, hour))}. */
    public static Instant utc(int year, int month, int day, int hour) {
        return LocalDate.of(year, 1, 1).plusMonths(month - 1L).plusDays(day - 1L)
                .atStartOfDay(ZoneOffset.UTC).plusHours(hour).toInstant();
    }

    /** {@code new Date("YYYY-MM-DD")}: midnight UTC of that day. */
    public static Instant utcMidnight(String isoDate) {
        return LocalDate.parse(isoDate.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Instant atUtcMidnight(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static LocalDate utcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    public static Instant plusDays(Instant instant, double days) {
        return instant.plusMillis(Math.round(days * DAY_MS));
    }
}
