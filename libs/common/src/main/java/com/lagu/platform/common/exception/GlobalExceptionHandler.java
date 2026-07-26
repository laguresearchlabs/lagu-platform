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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("INTERNAL_ERROR")
                        .message("An unexpected error occurred")
                        .build()));
    }
}
