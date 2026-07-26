package com.lagu.platform.common.exception;

import com.lagu.platform.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for a real, reproduced bug: RequirePermissionAspect throws
 * ResponseStatusException(FORBIDDEN) on a denied permission check, but with no dedicated handler
 * it fell through to the generic Exception handler and came back as 500 INTERNAL_ERROR instead
 * of 403 — every permission denial across the platform was indistinguishable from a server
 * crash. First observed via document-service's staffUser_cannotAccessPendingReview integration
 * test unexpectedly getting a 500.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void responseStatusExceptionPreservesItsOwnStatusCode() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleResponseStatus(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().isSuccess()).isFalse();
        assertThat(resp.getBody().getError().getMessage()).isEqualTo("Admin role required");
    }

    @Test
    void notFoundResponseStatusExceptionReturns404() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleResponseStatus(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void serverErrorResponseStatusExceptionStaysA5xxNotSwallowed() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream failed");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleResponseStatus(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
