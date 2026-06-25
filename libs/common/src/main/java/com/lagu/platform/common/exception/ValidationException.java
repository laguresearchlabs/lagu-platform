package com.lagu.platform.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

@Getter
public class ValidationException extends PlatformException {

    private final List<String> fieldErrors;

    public ValidationException(String message) {
        super("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
        this.fieldErrors = List.of();
    }

    public ValidationException(String context, List<String> fieldErrors) {
        super("VALIDATION_FAILED", "Validation failed for " + context, HttpStatus.BAD_REQUEST);
        this.fieldErrors = fieldErrors;
    }
}
