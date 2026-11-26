package com.fermapp.api.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base of every error the API returns on purpose. It becomes
 * {@code { "error": { "code", "message", "details"? } }} with the given status,
 * exactly like AppError in the TypeScript server.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
