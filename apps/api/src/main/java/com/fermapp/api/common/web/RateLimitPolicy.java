package com.fermapp.api.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;

/** The rate limiters of the TypeScript server, with their windows, limits and messages. */
public enum RateLimitPolicy {

    /** Every /api/v1 route: 600 requests per minute per client address. */
    GLOBAL(Duration.ofMinutes(1), 600, "Too many requests. Try again shortly.", false),
    /** Register, login and refresh: 20 attempts per 15 minutes. */
    CREDENTIALS(Duration.ofMinutes(15), 20, "Too many attempts. Try again in a few minutes.", false),
    /** The assistant: 20 questions per minute. */
    AI(Duration.ofMinutes(1), 20, "Too many questions in one minute. Try again shortly.", false),
    /** Sensor ingestion: 120 requests per minute per device key. */
    INGEST(Duration.ofMinutes(1), 120, "Too many requests, please try again later.", true);

    private final Duration window;
    private final int limit;
    private final String message;
    private final boolean keyedByDevice;

    RateLimitPolicy(Duration window, int limit, String message, boolean keyedByDevice) {
        this.window = window;
        this.limit = limit;
        this.message = message;
        this.keyedByDevice = keyedByDevice;
    }

    public Duration window() {
        return window;
    }

    /** In the test environment the limits are raised so the test suite is not throttled. */
    public int limit(boolean test) {
        if (!test) {
            return limit;
        }
        return this == GLOBAL ? 100_000 : 10_000;
    }

    public String message() {
        return message;
    }

    /** The JSON error envelope, except for the ingest limiter, which answered with plain text. */
    public boolean jsonBody() {
        return !keyedByDevice;
    }

    public String keyOf(HttpServletRequest request) {
        if (keyedByDevice) {
            String key = request.getHeader("x-device-key");
            String value = key == null ? "anonim" : key;
            return value.length() > 64 ? value.substring(0, 64) : value;
        }
        return request.getRemoteAddr();
    }
}
