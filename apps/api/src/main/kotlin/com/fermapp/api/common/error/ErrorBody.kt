package com.fermapp.api.common.error

import com.fasterxml.jackson.annotation.JsonInclude

/** The error envelope: `{ "error": { "code", "message", "details"? } }`. */
@JvmRecord
data class ErrorBody(val error: Error) {

    @JvmRecord
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Error(val code: String?, val message: String?, val details: Any?)

    /** One validation problem, in the shape of a zod issue: the dotted path and the message. */
    @JvmRecord
    data class FieldIssue(val field: String?, val message: String?)

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(code: String?, message: String?, details: Any? = null): ErrorBody = ErrorBody(Error(code, message, details))
    }
}
