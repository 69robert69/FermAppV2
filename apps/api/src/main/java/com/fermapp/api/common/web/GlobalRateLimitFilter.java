package com.fermapp.api.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** The global limit of 600 requests per minute on every /api/v1 route. */
public class GlobalRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    public GlobalRateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (limiter.allow(RateLimitPolicy.GLOBAL, request, response)) {
            chain.doFilter(request, response);
        }
    }
}
