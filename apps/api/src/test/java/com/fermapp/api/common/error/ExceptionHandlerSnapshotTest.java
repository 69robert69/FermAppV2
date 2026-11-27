package com.fermapp.api.common.error;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.Snapshots;
import com.fermapp.api.common.web.BodySizeLimitFilter;
import com.fermapp.api.config.JacksonConfig;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Pins the status and the JSON body the global exception handler gives every kind of error. */
@SpringBootTest(classes = {JacksonAutoConfiguration.class, JacksonConfig.class})
class ExceptionHandlerSnapshotTest {

    @Autowired
    private ObjectMapper mapper;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unused")
    void endpoint(@PathVariable UUID id, @RequestParam int year, Body body) {
    }

    record Inner(@NotNull String value) {
    }

    record Body(@NotNull @Size(min = 2) String name, @Valid Inner inner) {
    }

    private MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("endpoint", UUID.class, int.class, Body.class);
        MethodParameter parameter = new MethodParameter(method, index);
        parameter.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        return parameter;
    }

    private Map<String, Object> result(ResponseEntity<ErrorBody> response) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", response.getStatusCode().value());
        out.put("body", mapper.readTree(mapper.writeValueAsString(response.getBody())));
        return out;
    }

    @Test
    void errorResponsesMatchTheSnapshot() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/nowhere");

        out.put("apiNotFound", result(handler.api(Errors.notFound("Parcel"))));
        out.put("apiWithDetails", result(handler.api(Errors.badRequest("Bad.", List.of(new ErrorBody.FieldIssue("a", "b"))))));
        out.put("apiTeapot", result(handler.api(new ApiException(org.springframework.http.HttpStatus.I_AM_A_TEAPOT, "TEA", null))));

        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Body(null, null), "body");
        binding.addError(new FieldError("body", "name", "Required"));
        binding.addError(new FieldError("body", "inner.value", null));
        binding.addError(new ObjectError("body", "Send either the parcel or the outline."));
        out.put("invalidBody", result(handler.invalidBody(new MethodArgumentNotValidException(parameter(2), binding))));
        out.put("invalidQueryObject", result(handler.invalidQueryObject(new BindException(binding))));

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        // One violation only: the exception keeps them in a HashSet, so several would come out in any order.
        var violations = validator.validate(new Body("xx", new Inner(null)));
        var ordered = new java.util.TreeSet<jakarta.validation.ConstraintViolation<Body>>(
                java.util.Comparator.comparing(v -> v.getPropertyPath().toString()));
        ordered.addAll(violations);
        out.put("constraintViolation", result(handler.constraintViolation(
                new ConstraintViolationException(new java.util.LinkedHashSet<>(ordered)))));

        out.put("typeMismatchPath", result(handler.typeMismatch(new MethodArgumentTypeMismatchException("abc", UUID.class, "id",
                parameter(0), new IllegalArgumentException("bad uuid")))));
        out.put("typeMismatchQuery", result(handler.typeMismatch(new MethodArgumentTypeMismatchException("x", int.class, "year",
                parameter(1), null))));
        out.put("missingParameter", result(handler.missingParameter(new MissingServletRequestParameterException("year", "int"))));

        MockHttpInputMessage input = new MockHttpInputMessage(new byte[0]);
        Exception mappingError;
        try {
            mapper.readValue("{\"items\":[1,\"x\"]}", new TypeReference<Map<String, List<Integer>>>() { });
            throw new AssertionError("expected a mapping error");
        } catch (com.fasterxml.jackson.databind.JsonMappingException error) {
            mappingError = error;
        }
        Exception rootMappingError;
        try {
            mapper.readValue("\"text\"", Integer.class);
            throw new AssertionError("expected a mapping error");
        } catch (com.fasterxml.jackson.databind.JsonMappingException error) {
            rootMappingError = error;
        }
        List<Object> unreadable = new ArrayList<>();
        for (Throwable cause : new Throwable[] {new BodySizeLimitFilter.PayloadTooLargeException(),
                new IOException("wrapped", new BodySizeLimitFilter.PayloadTooLargeException()),
                new JsonParseException(null, "Unexpected character"), mappingError, rootMappingError,
                new IllegalStateException("other"), null}) {
            unreadable.add(result(handler.unreadable(new HttpMessageNotReadableException("unreadable", cause, input))));
        }
        out.put("unreadable", unreadable);

        out.put("unsupportedMediaType", result(handler.unsupportedMediaType(new HttpMediaTypeNotSupportedException("text/plain"))));
        out.put("uploadTooLarge", result(handler.uploadTooLarge(new MaxUploadSizeExceededException(20))));
        out.put("badUpload", List.of(result(handler.badUpload(new MultipartException("broken"))),
                result(handler.badUpload(new MissingServletRequestPartException("file")))));

        SQLException unique = new SQLException("duplicate key", "23505");
        out.put("integrityUnique", result(handler.integrity(new DataIntegrityViolationException("dup", unique), request)));
        out.put("integrityNested", result(handler.integrity(new DataIntegrityViolationException("dup",
                new RuntimeException(new RuntimeException(unique))), request)));
        out.put("integrityOther", result(handler.integrity(new DataIntegrityViolationException("fk",
                new SQLException("foreign key", "23503")), request)));
        out.put("isUniqueViolation", List.of(GlobalExceptionHandler.isUniqueViolation(null),
                GlobalExceptionHandler.isUniqueViolation(unique), GlobalExceptionHandler.isUniqueViolation(new SQLException("x")),
                GlobalExceptionHandler.isUniqueViolation(new RuntimeException(new SQLException("x", "23505")))));

        out.put("recordNotFound", List.of(result(handler.recordNotFound(new EntityNotFoundException("x"))),
                result(handler.recordNotFound(new EmptyResultDataAccessException(1)))));
        out.put("unknownRoute", List.of(
                result(handler.unknownRoute(new NoResourceFoundException(HttpMethod.PATCH, "/api/v1/nowhere"), request)),
                result(handler.unknownRoute(new HttpRequestMethodNotSupportedException("PATCH"), request))));
        out.put("unexpected", result(handler.unexpected(new IllegalStateException("boom"), request)));
        Snapshots.assertMatches(mapper, "exception-handler", out);
    }
}
