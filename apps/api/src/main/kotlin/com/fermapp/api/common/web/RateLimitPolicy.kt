package com.fermapp.api.common.web

import jakarta.servlet.http.HttpServletRequest
import java.time.Duration

/** The rate limiters of the TypeScript server, with their windows, limits and messages. */
enum class RateLimitPolicy(
    private val window: Duration,
    private val limit: Int,
    private val message: String,
    private val keyedByDevice: Boolean,
) {
    /** Every /api/v1 route: 600 requests per minute per client address. */
    GLOBAL(Duration.ofMinutes(1), 600, "Too many requests. Try again shortly.", false),

    /** Register, login and refresh: 20 attempts per 15 minutes. */
    CREDENTIALS(Duration.ofMinutes(15), 20, "Too many attempts. Try again in a few minutes.", false),

    /** The assistant: 20 questions per minute. */
    AI(Duration.ofMinutes(1), 20, "Too many questions in one minute. Try again shortly.", false),

    /** Sensor ingestion: 120 requests per minute per device key. */
    INGEST(Duration.ofMinutes(1), 120, "Too many requests, please try again later.", true),
    ;

    fun window(): Duration = window

    /** In the test environment the limits are raised so the test suite is not throttled. */
    fun limit(test: Boolean): Int = when {
        !test -> limit
        this == GLOBAL -> 100_000
        else -> 10_000
    }

    fun message(): String = message

    /** The JSON error envelope, except for the ingest limiter, which answered with plain text. */
    fun jsonBody(): Boolean = !keyedByDevice

    fun keyOf(request: HttpServletRequest): String =
        if (keyedByDevice) (request.getHeader("x-device-key") ?: "anonim").take(64) else request.remoteAddr
}
