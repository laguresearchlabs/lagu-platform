package com.lagu.platform.search.config;

import com.lagu.platform.common.dto.ApiError;
import com.lagu.platform.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

@RestControllerAdvice
@Slf4j
public class SearchExceptionHandler {

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleOpenSearchError(IOException ex) {
        log.error("OpenSearch I/O error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ApiError.builder()
                        .code("SEARCH_BACKEND_UNAVAILABLE")
                        .message("Search backend unavailable")
                        .details(ex.getMessage() != null ? java.util.List.of(ex.getMessage()) : null)
                        .build()));
    }
}
