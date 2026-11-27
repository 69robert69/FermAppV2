package com.fermapp.api.common.error

import org.springframework.http.HttpStatus

/**
 * Factory methods for the API errors, with the same messages as lib/errors.ts.
 * Each one returns a typed subclass of [ApiException], so callers write
 * `throw Errors.notFound("Parcel")`.
 */
object Errors {

    @JvmStatic
    @JvmOverloads
    fun badRequest(message: String, details: Any? = null) = BadRequestException(message, details)

    @JvmStatic
    @JvmOverloads
    fun unauthorized(message: String = "Authentication required.") = UnauthorizedException(message)

    @JvmStatic
    @JvmOverloads
    fun forbidden(message: String = "You do not have permission for this action.") = ForbiddenException(message)

    @JvmStatic
    fun notFound(what: String) = NotFoundException(what)

    @JvmStatic
    fun conflict(message: String) = ConflictException(message)

    @JvmStatic
    fun notConfigured(service: String) = ServiceNotConfiguredException(service)

    @JvmStatic
    fun upstreamFailed(service: String) = UpstreamFailedException(service)

    @JvmStatic
    fun recordNotFound() = RecordNotFoundException()

    /** 400 BAD_REQUEST. */
    class BadRequestException(message: String, details: Any?) :
        ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, details)

    /** 401 UNAUTHORIZED: missing, invalid or expired credentials. */
    class UnauthorizedException(message: String) : ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message)

    /** 403 FORBIDDEN: the role or the ownership does not allow the action. */
    class ForbiddenException(message: String) : ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message)

    /** 404 NOT_FOUND, with the message "{what} was not found.". */
    class NotFoundException(what: String) : ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what was not found.")

    /** 409 CONFLICT: the current state does not allow the operation. */
    class ConflictException(message: String) : ApiException(HttpStatus.CONFLICT, "CONFLICT", message)

    /** 503 SERVICE_NOT_CONFIGURED: an optional integration has no credentials. */
    class ServiceNotConfiguredException(service: String) :
        ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_NOT_CONFIGURED", "$service is not configured on the server.")

    /** 502 UPSTREAM_FAILED: an external service did not answer after the retries. */
    class UpstreamFailedException(service: String) :
        ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_FAILED", "$service is not responding right now. Try again later.")

    /** 404 "The record was not found.": the equivalent of Prisma P2025. */
    class RecordNotFoundException : ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The record was not found.")
}
