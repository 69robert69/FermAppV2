package com.fermapp.api.common.web

/** Applies a named, route-specific rate limit (on top of the global one). */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class RateLimited(val value: RateLimitPolicy)
