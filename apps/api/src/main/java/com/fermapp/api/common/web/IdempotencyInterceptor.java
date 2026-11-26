package com.fermapp.api.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AccessInterceptor;
import com.fermapp.api.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs after authentication for authenticated, non-GET routes that carry an Idempotency-Key.
 * It reserves the key or replays the saved response with {@code Idempotency-Replayed: true}.
 * {@link IdempotencyFilter} stores the response once the handler has finished.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String RESERVATION_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".reservation";

    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public IdempotencyInterceptor(IdempotencyService idempotency, ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method) || "GET".equals(request.getMethod())) {
            return true;
        }
        Access access = AccessInterceptor.accessOf(method);
        AuthContext auth = AccessInterceptor.current();
        String key = request.getHeader("Idempotency-Key");
        if (access == null || access.value() == AccessGroup.PUBLIC || auth == null || key == null || key.isEmpty()) {
            return true;
        }
        String route = request.getMethod() + " " + request.getRequestURI();
        IdempotencyService.Outcome outcome = idempotency.reserve(auth.userId(), key, route);
        if (outcome instanceof IdempotencyService.Reserved reserved) {
            request.setAttribute(RESERVATION_ATTRIBUTE, reserved.reservationId());
            return true;
        }
        IdempotencyService.Replay replay = (IdempotencyService.Replay) outcome;
        response.setHeader("Idempotency-Replayed", "true");
        response.setStatus(replay.statusCode());
        if (replay.statusCode() != 204) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getOutputStream(), replay.response());
        }
        return false;
    }
}
