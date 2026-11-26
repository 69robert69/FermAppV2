package com.fermapp.api.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The error envelope: {@code { "error": { "code", "message", "details"? } }}. */
public record ErrorBody(Error error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String code, String message, Object details) {
    }

    /** One validation problem, in the shape of a zod issue: the dotted path and the message. */
    public record FieldIssue(String field, String message) {
    }

    public static ErrorBody of(String code, String message) {
        return new ErrorBody(new Error(code, message, null));
    }

    public static ErrorBody of(String code, String message, Object details) {
        return new ErrorBody(new Error(code, message, details));
    }
}
