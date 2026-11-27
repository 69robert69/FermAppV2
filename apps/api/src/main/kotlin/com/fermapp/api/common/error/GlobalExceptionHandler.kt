package com.fermapp.api.common.error

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fermapp.api.common.error.ErrorBody.FieldIssue
import com.fermapp.api.common.web.BodySizeLimitFilter
import jakarta.persistence.EntityNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import java.sql.SQLException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException
import org.springframework.validation.BindException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Turns every error into the API's error envelope with the same status codes as
 * middleware/error.ts: AppError, invalid input (400), upload errors, unique-constraint
 * conflicts (409), missing records (404), bad or oversized JSON, unknown routes and,
 * for anything unexpected, a logged 500 INTERNAL_ERROR.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private fun body(status: HttpStatus, code: String, message: String?, details: Any?): ResponseEntity<ErrorBody> =
        ResponseEntity.status(status).body(ErrorBody.of(code, message, details))

    private fun invalid(where: String, issues: List<FieldIssue>): ResponseEntity<ErrorBody> =
        body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid data in $where.", issues)

    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException): ResponseEntity<ErrorBody> = body(error.status(), error.code(), error.message, error.details())

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(error: MethodArgumentNotValidException): ResponseEntity<ErrorBody> {
        val issues = error.bindingResult.fieldErrors.map { FieldIssue(it.field, it.defaultMessage) } +
            error.bindingResult.globalErrors.map { FieldIssue("", it.defaultMessage) }
        return invalid(BODY, issues)
    }

    @ExceptionHandler(BindException::class)
    fun invalidQueryObject(error: BindException): ResponseEntity<ErrorBody> =
        invalid(QUERY, error.fieldErrors.map { FieldIssue(it.field, it.defaultMessage) })

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParameters(error: HandlerMethodValidationException): ResponseEntity<ErrorBody> {
        val results = error.parameterValidationResults
        val inPath = results.any { it.methodParameter.hasParameterAnnotation(PathVariable::class.java) }
        val issues = results.flatMap { result ->
            result.resolvableErrors.map { FieldIssue(result.methodParameter.parameterName, it.defaultMessage) }
        }
        return invalid(if (inPath) URL else QUERY, issues)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun constraintViolation(error: ConstraintViolationException): ResponseEntity<ErrorBody> =
        invalid(QUERY, error.constraintViolations.map { FieldIssue(lastNode(it.propertyPath.toString()), it.message) })

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(error: MethodArgumentTypeMismatchException): ResponseEntity<ErrorBody> {
        val inPath = error.parameter.hasParameterAnnotation(PathVariable::class.java)
        return invalid(if (inPath) URL else QUERY, listOf(FieldIssue(error.name, "Invalid value.")))
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(error: MissingServletRequestParameterException): ResponseEntity<ErrorBody> =
        invalid(QUERY, listOf(FieldIssue(error.parameterName, "Required")))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(error: HttpMessageNotReadableException): ResponseEntity<ErrorBody> {
        val cause = error.cause
        return when {
            cause is BodySizeLimitFilter.PayloadTooLargeException || cause?.cause is BodySizeLimitFilter.PayloadTooLargeException ->
                body(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "The request body is too large.", null)
            cause is JsonParseException -> body(HttpStatus.BAD_REQUEST, "BAD_JSON", "The request body is not valid JSON.", null)
            cause is JsonMappingException -> {
                val field = cause.path.joinToString(".") { it.fieldName ?: it.index.toString() }
                invalid(BODY, listOf(FieldIssue(field, "Invalid value.")))
            }
            else -> invalid(BODY, listOf(FieldIssue("", "Required")))
        }
    }

    // express.json() ignored other content types, so the body was missing and validation failed.
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun unsupportedMediaType(error: HttpMediaTypeNotSupportedException): ResponseEntity<ErrorBody> =
        invalid(BODY, listOf(FieldIssue("", "Required")))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun uploadTooLarge(error: MaxUploadSizeExceededException): ResponseEntity<ErrorBody> =
        body(HttpStatus.BAD_REQUEST, "BAD_UPLOAD", "The file exceeds the 20 MB limit.", null)

    @ExceptionHandler(MultipartException::class, MissingServletRequestPartException::class)
    fun badUpload(error: Exception): ResponseEntity<ErrorBody> = body(HttpStatus.BAD_REQUEST, "BAD_UPLOAD", "Invalid file.", null)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(error: DataIntegrityViolationException, request: HttpServletRequest): ResponseEntity<ErrorBody> =
        if (isUniqueViolation(error)) {
            body(HttpStatus.CONFLICT, "CONFLICT", "A record with this data already exists.", null)
        } else {
            unexpected(error, request)
        }

    @ExceptionHandler(EntityNotFoundException::class, EmptyResultDataAccessException::class, JpaObjectRetrievalFailureException::class)
    fun recordNotFound(error: Exception): ResponseEntity<ErrorBody> =
        body(HttpStatus.NOT_FOUND, "NOT_FOUND", "The record was not found.", null)

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class, HttpRequestMethodNotSupportedException::class)
    fun unknownRoute(error: Exception, request: HttpServletRequest): ResponseEntity<ErrorBody> =
        body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Route ${request.method} ${request.requestURI} does not exist.", null)

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception, request: HttpServletRequest): ResponseEntity<ErrorBody> {
        log.error("Unexpected error on {} {}", request.method, request.requestURI, error)
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred.", null)
    }

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
        private const val BODY = "the request body"
        private const val QUERY = "the query parameters"
        private const val URL = "the URL"

        private fun lastNode(path: String): String = path.substringAfterLast('.')

        /** SQLState 23505 is a unique-constraint violation in PostgreSQL (Prisma's P2002). */
        @JvmStatic
        fun isUniqueViolation(error: Throwable?): Boolean =
            generateSequence(error) { it.cause }.any { it is SQLException && it.sqlState == "23505" }
    }
}
