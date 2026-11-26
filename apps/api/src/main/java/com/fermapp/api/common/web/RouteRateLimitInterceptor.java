package com.fermapp.api.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies {@link RateLimited} before authentication, as the route limiters did in Express. */
@Component
public class RouteRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RouteRateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (handler instanceof HandlerMethod method) {
            RateLimited limited = method.getMethodAnnotation(RateLimited.class);
            if (limited != null) {
                return limiter.allow(limited.value(), request, response);
            }
        }
        return true;
    }
}
