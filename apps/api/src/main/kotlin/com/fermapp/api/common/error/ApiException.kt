package com.fermapp.api.common.error

import org.springframework.http.HttpStatus

/**
 * Base of every error the API returns on purpose. It becomes
 * `{ "error": { "code", "message", "details"? } }` with the given status,
 * exactly like AppError in the TypeScript server.
 */
open class ApiException @JvmOverloads constructor(
    private val status: HttpStatus,
    private val code: String,
    message: String?,
    @Transient private val details: Any? = null,
) : RuntimeException(message) {

    // Record-style accessors, as the Java callers and the exception handler read them.
    fun status(): HttpStatus = status

    fun code(): String = code

    fun details(): Any? = details
}
