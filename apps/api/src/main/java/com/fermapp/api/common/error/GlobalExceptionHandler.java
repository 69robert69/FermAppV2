package com.fermapp.api.common.error;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fermapp.api.common.error.ErrorBody.FieldIssue;
import com.fermapp.api.common.web.BodySizeLimitFilter;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every error into the API's error envelope with the same status codes as
 * middleware/error.ts: AppError, invalid input (400), upload errors, unique-constraint
 * conflicts (409), missing records (404), bad or oversized JSON, unknown routes and,
 * for anything unexpected, a logged 500 INTERNAL_ERROR.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BODY = "the request body";
    private static final String QUERY = "the query parameters";
    private static final String URL = "the URL";

    private static ResponseEntity<ErrorBody> body(HttpStatus status, String code, String message, Object details) {
        return ResponseEntity.status(status).body(ErrorBody.of(code, message, details));
    }

    private static ResponseEntity<ErrorBody> invalid(String where, List<FieldIssue> issues) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid data in " + where + ".", issues);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException error) {
        return body(error.status(), error.code(), error.getMessage(), error.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException error) {
        List<FieldIssue> issues = error.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldIssue(field.getField(), field.getDefaultMessage()))
                .collect(Collectors.toList());
        error.getBindingResult().getGlobalErrors()
                .forEach(global -> issues.add(new FieldIssue("", global.getDefaultMessage())));
        return invalid(BODY, issues);
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ErrorBody> invalidQueryObject(BindException error) {
        return invalid(QUERY, error.getFieldErrors().stream()
                .map(field -> new FieldIssue(field.getField(), field.getDefaultMessage())).toList());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorBody> invalidParameters(HandlerMethodValidationException error) {
        boolean inPath = error.getParameterValidationResults().stream()
                .anyMatch(result -> result.getMethodParameter().hasParameterAnnotation(PathVariable.class));
        List<FieldIssue> issues = error.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(resolvable -> new FieldIssue(result.getMethodParameter().getParameterName(), resolvable.getDefaultMessage())))
                .toList();
        return invalid(inPath ? URL : QUERY, issues);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorBody> constraintViolation(ConstraintViolationException error) {
        return invalid(QUERY, error.getConstraintViolations().stream()
                .map(violation -> new FieldIssue(lastNode(violation.getPropertyPath().toString()), violation.getMessage()))
                .toList());
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException error) {
        boolean inPath = error.getParameter().hasParameterAnnotation(PathVariable.class);
        return invalid(inPath ? URL : QUERY, List.of(new FieldIssue(error.getName(), "Invalid value.")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorBody> missingParameter(MissingServletRequestParameterException error) {
        return invalid(QUERY, List.of(new FieldIssue(error.getParameterName(), "Required")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException error) {
        Throwable cause = error.getCause();
        if (cause instanceof BodySizeLimitFilter.PayloadTooLargeException
                || (cause != null && cause.getCause() instanceof BodySizeLimitFilter.PayloadTooLargeException)) {
            return body(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "The request body is too large.", null);
        }
        if (cause instanceof JsonParseException) {
            return body(HttpStatus.BAD_REQUEST, "BAD_JSON", "The request body is not valid JSON.", null);
        }
        if (cause instanceof JsonMappingException mapping) {
            String field = mapping.getPath().stream()
                    .map(reference -> reference.getFieldName() != null ? reference.getFieldName() : String.valueOf(reference.getIndex()))
                    .collect(Collectors.joining("."));
            return invalid(BODY, List.of(new FieldIssue(field, "Invalid value.")));
        }
        return invalid(BODY, List.of(new FieldIssue("", "Required")));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> unsupportedMediaType(HttpMediaTypeNotSupportedException error) {
        // express.json() ignored other content types, so the body was missing and validation failed.
        return invalid(BODY, List.of(new FieldIssue("", "Required")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> uploadTooLarge(MaxUploadSizeExceededException error) {
        return body(HttpStatus.BAD_REQUEST, "BAD_UPLOAD", "The file exceeds the 20 MB limit.", null);
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    ResponseEntity<ErrorBody> badUpload(Exception error) {
        return body(HttpStatus.BAD_REQUEST, "BAD_UPLOAD", "Invalid file.", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> integrity(DataIntegrityViolationException error, HttpServletRequest request) {
        if (isUniqueViolation(error)) {
            return body(HttpStatus.CONFLICT, "CONFLICT", "A record with this data already exists.", null);
        }
        return unexpected(error, request);
    }

    /** SQLState 23505 is a unique-constraint violation in PostgreSQL (Prisma's P2002). */
    public static boolean isUniqueViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler({EntityNotFoundException.class, EmptyResultDataAccessException.class, JpaObjectRetrievalFailureException.class})
    ResponseEntity<ErrorBody> recordNotFound(Exception error) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "The record was not found.", null);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ErrorBody> unknownRoute(Exception error, HttpServletRequest request) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Route " + request.getMethod() + " " + request.getRequestURI() + " does not exist.", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception error, HttpServletRequest request) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), error);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred.", null);
    }
}
