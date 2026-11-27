package com.fermapp.api.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/** The global limit of 600 requests per minute on every /api/v1 route. */
class GlobalRateLimitFilter(private val limiter: RateLimiter) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (limiter.allow(RateLimitPolicy.GLOBAL, request, response)) {
            chain.doFilter(request, response)
        }
    }
}
