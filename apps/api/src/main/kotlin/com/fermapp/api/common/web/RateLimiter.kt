package com.fermapp.api.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.error.ErrorBody
import com.fermapp.api.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Fixed-window rate limiting in memory, per server instance, like the default store of
 * express-rate-limit. It writes the IETF draft-7 headers (`RateLimit-Policy`, `RateLimit`)
 * and answers 429 RATE_LIMITED when a window is used up.
 */
@Component
class RateLimiter(private val properties: AppProperties, private val mapper: ObjectMapper) {

    private class Window(val resetAt: Long, val hits: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()

    /** Counts the request; returns false after writing the 429 response when the limit is reached. */
    @Throws(IOException::class)
    fun allow(policy: RateLimitPolicy, request: HttpServletRequest, response: HttpServletResponse): Boolean {
        val now = System.currentTimeMillis()
        val windowMs = policy.window().toMillis()
        val key = policy.name + ":" + policy.keyOf(request)
        val window = windows.compute(key) { _, current ->
            if (current == null || current.resetAt <= now) Window(now + windowMs, AtomicInteger()) else current
        }!!
        val hits = window.hits.incrementAndGet()
        val limit = policy.limit(properties.isTest())
        val resetSeconds = Math.max(0L, Math.ceil((window.resetAt - now) / 1000.0).toLong())

        response.setHeader("RateLimit-Policy", "$limit;w=${policy.window().toSeconds()}")
        response.setHeader("RateLimit", "limit=$limit, remaining=${Math.max(0, limit - hits)}, reset=$resetSeconds")
        if (hits <= limit) {
            return true
        }
        response.setHeader("Retry-After", resetSeconds.toString())
        response.status = 429
        response.characterEncoding = "UTF-8"
        if (policy.jsonBody()) {
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            mapper.writeValue(response.outputStream, ErrorBody.of("RATE_LIMITED", policy.message()))
        } else {
            // The ingest limiter had no custom message, so express-rate-limit sent its default text.
            response.contentType = "text/html; charset=utf-8"
            response.writer.write(policy.message())
        }
        return false
    }

    /** Drops expired windows so memory stays bounded. */
    @Scheduled(fixedDelay = 60_000)
    fun evictExpired() {
        val now = System.currentTimeMillis()
        windows.entries.removeIf { it.value.resetAt <= now }
    }
}
