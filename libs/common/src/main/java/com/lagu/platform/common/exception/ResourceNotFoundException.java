package com.lagu.platform.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends PlatformException {

    public ResourceNotFoundException(String resourceType, String id) {
        super("NOT_FOUND", resourceType + " not found: " + id, HttpStatus.NOT_FOUND);
    }
}
