package com.fermapp.api.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.error.ErrorBody;
import com.fermapp.api.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiting in memory, per server instance, like the default store of
 * express-rate-limit. It writes the IETF draft-7 headers ({@code RateLimit-Policy},
 * {@code RateLimit}) and answers 429 RATE_LIMITED when a window is used up.
 */
@Component
public class RateLimiter {

    private record Window(long resetAt, AtomicInteger hits) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public RateLimiter(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** Counts the request; returns false after writing the 429 response when the limit is reached. */
    public boolean allow(RateLimitPolicy policy, HttpServletRequest request, HttpServletResponse response) throws IOException {
        long now = System.currentTimeMillis();
        long windowMs = policy.window().toMillis();
        String key = policy.name() + ":" + policy.keyOf(request);
        Window window = windows.compute(key, (k, current) ->
                current == null || current.resetAt() <= now ? new Window(now + windowMs, new AtomicInteger()) : current);
        int hits = window.hits().incrementAndGet();
        int limit = policy.limit(properties.isTest());
        long resetSeconds = Math.max(0, (long) Math.ceil((window.resetAt() - now) / 1000.0));

        response.setHeader("RateLimit-Policy", limit + ";w=" + policy.window().toSeconds());
        response.setHeader("RateLimit", "limit=" + limit + ", remaining=" + Math.max(0, limit - hits) + ", reset=" + resetSeconds);
        if (hits <= limit) {
            return true;
        }
        response.setHeader("Retry-After", String.valueOf(resetSeconds));
        response.setStatus(429);
        response.setCharacterEncoding("UTF-8");
        if (policy.jsonBody()) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ErrorBody.of("RATE_LIMITED", policy.message()));
        } else {
            // The ingest limiter had no custom message, so express-rate-limit sent its default text.
            response.setContentType("text/html; charset=utf-8");
            response.getWriter().write(policy.message());
        }
        return false;
    }

    /** Drops expired windows so memory stays bounded. */
    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().resetAt() <= now);
    }
}
