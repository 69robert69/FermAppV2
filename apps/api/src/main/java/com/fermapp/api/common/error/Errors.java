package com.fermapp.api.common.error;

import org.springframework.http.HttpStatus;

/**
 * Factory methods for the API errors, with the same messages as lib/errors.ts.
 * Each one returns a typed subclass of {@link ApiException}, so callers write
 * {@code throw Errors.notFound("Parcel")}.
 */
public final class Errors {

    private Errors() {
    }

    public static BadRequestException badRequest(String message) {
        return new BadRequestException(message, null);
    }

    public static BadRequestException badRequest(String message, Object details) {
        return new BadRequestException(message, details);
    }

    public static UnauthorizedException unauthorized() {
        return new UnauthorizedException("Authentication required.");
    }

    public static UnauthorizedException unauthorized(String message) {
        return new UnauthorizedException(message);
    }

    public static ForbiddenException forbidden() {
        return new ForbiddenException("You do not have permission for this action.");
    }

    public static ForbiddenException forbidden(String message) {
        return new ForbiddenException(message);
    }

    public static NotFoundException notFound(String what) {
        return new NotFoundException(what);
    }

    public static ConflictException conflict(String message) {
        return new ConflictException(message);
    }

    public static ServiceNotConfiguredException notConfigured(String service) {
        return new ServiceNotConfiguredException(service);
    }

    public static UpstreamFailedException upstreamFailed(String service) {
        return new UpstreamFailedException(service);
    }

    public static RecordNotFoundException recordNotFound() {
        return new RecordNotFoundException();
    }

    /** 400 BAD_REQUEST. */
    public static final class BadRequestException extends ApiException {
        public BadRequestException(String message, Object details) {
            super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, details);
        }
    }

    /** 401 UNAUTHORIZED: missing, invalid or expired credentials. */
    public static final class UnauthorizedException extends ApiException {
        public UnauthorizedException(String message) {
            super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
        }
    }

    /** 403 FORBIDDEN: the role or the ownership does not allow the action. */
    public static final class ForbiddenException extends ApiException {
        public ForbiddenException(String message) {
            super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
        }
    }

    /** 404 NOT_FOUND, with the message "{what} was not found.". */
    public static final class NotFoundException extends ApiException {
        public NotFoundException(String what) {
            super(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " was not found.");
        }
    }

    /** 409 CONFLICT: the current state does not allow the operation. */
    public static final class ConflictException extends ApiException {
        public ConflictException(String message) {
            super(HttpStatus.CONFLICT, "CONFLICT", message);
        }
    }

    /** 503 SERVICE_NOT_CONFIGURED: an optional integration has no credentials. */
    public static final class ServiceNotConfiguredException extends ApiException {
        public ServiceNotConfiguredException(String service) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_NOT_CONFIGURED", service + " is not configured on the server.");
        }
    }

    /** 502 UPSTREAM_FAILED: an external service did not answer after the retries. */
    public static final class UpstreamFailedException extends ApiException {
        public UpstreamFailedException(String service) {
            super(HttpStatus.BAD_GATEWAY, "UPSTREAM_FAILED", service + " is not responding right now. Try again later.");
        }
    }

    /** 404 "The record was not found.": the equivalent of Prisma P2025. */
    public static final class RecordNotFoundException extends ApiException {
        public RecordNotFoundException() {
            super(HttpStatus.NOT_FOUND, "NOT_FOUND", "The record was not found.");
        }
    }
}
