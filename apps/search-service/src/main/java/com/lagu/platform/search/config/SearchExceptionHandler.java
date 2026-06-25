package com.lagu.platform.search.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class SearchExceptionHandler {

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleOpenSearchError(IOException ex) {
        log.error("OpenSearch I/O error: {}", ex.getMessage(), ex);
        return Map.of("error", "Search backend unavailable", "detail", ex.getMessage());
    }
}
