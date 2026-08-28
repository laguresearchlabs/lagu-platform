package com.lagu.platform.common.exception;

import com.lagu.platform.common.dto.ApiError;
import com.lagu.platform.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.NoSuchElementException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.builder()
                        .code(ex.getCode())
                        .message(ex.getMessage())
                        .build()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(ValidationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.builder()
                        .code(ex.getCode())
                        .message(ex.getMessage())
                        .details(ex.getFieldErrors())
                        .build()));
    }

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlatform(PlatformException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.fail(ApiError.builder()
                        .code(ex.getCode())
                        .message(ex.getMessage())
                        .build()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.builder()
                        .code("VALIDATION_FAILED")
                        .message("Request validation failed")
                        .details(errors)
                        .build()));
    }

    /**
     * Without this, RequirePermissionAspect's ResponseStatusException(FORBIDDEN) (and any other
     * ResponseStatusException thrown anywhere in the platform) fell through to the generic
     * Exception handler below and came back as 500 INTERNAL_ERROR — every permission denial was
     * indistinguishable from a genuine server crash to callers and to monitoring alike.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            log.error("Unhandled ResponseStatusException", ex);
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(ApiError.builder()
                        .code(status.name())
                        .message(ex.getReason() != null ? ex.getReason() : status.getReasonPhrase())
                        .build()));
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("CONCURRENT_MODIFICATION")
                        .message("The resource was modified by another request; reload and retry")
                        .build()));
    }

    // ── Client-error mappings ─────────────────────────────────────────────────
    //
    // Everything below previously fell through to handleGeneric() and came back as
    // 500 INTERNAL_ERROR "An unexpected error occurred" — confirmed against the running stack for
    // a missing query parameter, a malformed UUID path variable, an unparseable JSON body, and a
    // lookup of a vendor that does not exist. Two costs, both paid platform-wide because every
    // service shares this advice:
    //
    //   * A caller cannot tell "you sent something wrong" from "the server is broken", so the
    //     only sensible response to a 500 — retry — is exactly the wrong one for a request that
    //     will never succeed however many times it is sent.
    //   * Ordinary client mistakes are indistinguishable from outages in the 5xx rate that
    //     everything alerts on, so a real incident arrives buried in noise.
    //
    // These log at WARN, not ERROR: a bad request is not a server fault, and filing it in the
    // error log is the same category mistake in a different place.

    /**
     * A lookup that found nothing. Kept alongside {@link ResourceNotFoundException} because
     * vendor-service throws the JDK exception directly and its README already documents this as
     * being handled here — it never was.
     *
     * Safe to map globally: the only deliberate throws in the platform are vendor-service's two
     * "Vendor not found" lookups, and there is no bare {@code Optional.get()} anywhere whose
     * failure this could disguise as a 404.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoSuchElement(NoSuchElementException ex) {
        log.warn("Not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("NOT_FOUND")
                        .message(ex.getMessage() != null ? ex.getMessage() : "Resource not found")
                        .build()));
    }

    /** A required query parameter was omitted. */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getParameterName());
        return badRequest("MISSING_PARAMETER",
                "Required parameter " + quote(ex.getParameterName()) + " is missing",
                List.of(ex.getParameterName() + ": expected " + ex.getParameterType()));
    }

    /**
     * A path variable or parameter that could not be converted — most often a malformed UUID.
     * The offending value is deliberately not echoed back: it is caller-controlled and would land
     * in logs and error surfaces verbatim.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "the expected type";
        log.warn("Type mismatch for {}: expected {}", ex.getName(), expected);
        return badRequest("INVALID_PARAMETER",
                "Parameter " + quote(ex.getName()) + " is not a valid " + expected,
                List.of(ex.getName() + ": expected " + expected));
    }

    /**
     * An unreadable or absent request body. The exception's own message carries Jackson's parser
     * detail — source position and internal class names — so it is logged rather than returned.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return badRequest("MALFORMED_REQUEST_BODY",
                "Request body is missing or is not valid JSON", null);
    }

    /** Bean validation on a @RequestParam/@PathVariable of an @Validated controller. */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations() == null ? List.of()
                : ex.getConstraintViolations().stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .sorted()
                        .toList();
        log.warn("Constraint violation: {}", errors);
        return badRequest("VALIDATION_FAILED", "Request validation failed", errors);
    }

    /**
     * A path no controller maps. Spring routes these to the static-resource handler, which throws
     * this — so before it was mapped, every request to a URL that simply does not exist came back
     * 500 "An unexpected error occurred". A typo in a client, or a call to an endpoint that has
     * since moved, was indistinguishable from the server falling over.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.warn("No handler for {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("NO_SUCH_ENDPOINT")
                        .message("No endpoint exists at that path")
                        .build()));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("METHOD_NOT_ALLOWED")
                        .message("HTTP " + ex.getMethod() + " is not supported by this endpoint")
                        .build()));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        log.warn("Unsupported media type: {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("UNSUPPORTED_MEDIA_TYPE")
                        .message("Content-Type is not supported by this endpoint; use application/json")
                        .build()));
    }

    /**
     * A write that violated a database constraint — a duplicate on a unique index, or a foreign
     * key. 409 rather than 400: the request was well formed, and the conflict is with state, which
     * is also what makes it worth retrying after a re-read. The driver's message names tables,
     * columns and index names, so it is logged rather than returned.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("CONSTRAINT_VIOLATION")
                        .message("The request conflicts with existing data")
                        .build()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("INTERNAL_ERROR")
                        .message("An unexpected error occurred")
                        .build()));
    }

    private static ResponseEntity<ApiResponse<Void>> badRequest(String code, String message,
                                                                List<String> details) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.builder()
                        .code(code)
                        .message(message)
                        .details(details)
                        .build()));
    }

    private static String quote(String value) {
        return "'" + value + "'";
    }
}
