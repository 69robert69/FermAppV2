package com.fermapp.api.common.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/** Applies [RateLimited] before authentication, as the route limiters did in Express. */
@Component
class RouteRateLimitInterceptor(private val limiter: RateLimiter) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val limited = (handler as? HandlerMethod)?.getMethodAnnotation(RateLimited::class.java) ?: return true
        return limiter.allow(limited.value, request, response)
    }
}
