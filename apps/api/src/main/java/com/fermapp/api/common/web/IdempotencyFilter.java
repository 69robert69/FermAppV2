package com.fermapp.api.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Captures the response of a reserved operation: a success is saved for replays, an error
 * releases the reservation so the operation can be sent again.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public IdempotencyFilter(IdempotencyService idempotency, ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        return "GET".equals(request.getMethod()) || key == null || key.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            Object reservation = request.getAttribute(IdempotencyInterceptor.RESERVATION_ATTRIBUTE);
            if (reservation instanceof UUID id) {
                int status = wrapper.getStatus();
                if (status >= 400) {
                    idempotency.release(id);
                } else {
                    byte[] body = wrapper.getContentAsByteArray();
                    JsonNode payload = body.length == 0 ? null : mapper.readTree(body);
                    idempotency.complete(id, status, payload);
                }
            }
            wrapper.copyBodyToResponse();
        }
    }
}
