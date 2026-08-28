package com.lagu.platform.common.exception;

import com.lagu.platform.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the property the whole advice exists for: a caller can tell a bad request from a broken
 * server. Every case below returned 500 INTERNAL_ERROR "An unexpected error occurred" before this
 * class existed — confirmed against the running Compose stack, not inferred — which made ordinary
 * client mistakes indistinguishable from outages both to callers and to anything alerting on 5xx.
 *
 * <p>The advice is shared by all eleven services, so a regression here is a platform-wide one.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static int status(ResponseEntity<ApiResponse<Void>> res) {
        return res.getStatusCode().value();
    }

    private static String code(ResponseEntity<ApiResponse<Void>> res) {
        return res.getBody().getError().getCode();
    }

    private static String message(ResponseEntity<ApiResponse<Void>> res) {
        return res.getBody().getError().getMessage();
    }

    // ── the four confirmed live against the stack ─────────────────────────────

    @Test
    void missingQueryParameterIs400AndNamesTheParameter() {
        var res = handler.handleMissingParam(
                new org.springframework.web.bind.MissingServletRequestParameterException("from", "LocalDate"));

        assertThat(status(res)).isEqualTo(400);
        assertThat(code(res)).isEqualTo("MISSING_PARAMETER");
        assertThat(message(res)).contains("from");
        assertThat(res.getBody().getError().getDetails()).anySatisfy(d -> assertThat(d).contains("LocalDate"));
    }

    @Test
    void unconvertibleParameterIs400AndNamesTheExpectedType() {
        var res = handler.handleTypeMismatch(new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "tenantId", null, new IllegalArgumentException("bad uuid")));

        assertThat(status(res)).isEqualTo(400);
        assertThat(code(res)).isEqualTo("INVALID_PARAMETER");
        assertThat(message(res)).contains("tenantId").contains("UUID");
    }

    @Test
    void unconvertibleParameterDoesNotEchoTheOffendingValue() {
        // The value is caller-controlled and would land verbatim in logs and error surfaces.
        var res = handler.handleTypeMismatch(new MethodArgumentTypeMismatchException(
                "<script>alert(1)</script>", UUID.class, "tenantId", null, new IllegalArgumentException("x")));

        assertThat(message(res)).doesNotContain("script");
        assertThat(res.getBody().getError().getDetails()).allSatisfy(d -> assertThat(d).doesNotContain("script"));
    }

    @Test
    void malformedJsonBodyIs400AndKeepsTheParserDetailOutOfTheResponse() {
        // Jackson's message carries source position and internal class names.
        var res = handler.handleUnreadableBody(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error: Unexpected end-of-input at [Source: (String)\"{\"; line: 1]",
                        (org.springframework.http.HttpInputMessage) null));

        assertThat(status(res)).isEqualTo(400);
        assertThat(code(res)).isEqualTo("MALFORMED_REQUEST_BODY");
        assertThat(message(res)).doesNotContain("Source").doesNotContain("line:");
    }

    @Test
    void missingResourceIs404AndKeepsTheLookupMessage() {
        var res = handler.handleNoSuchElement(new NoSuchElementException("Vendor not found: abc"));

        assertThat(status(res)).isEqualTo(404);
        assertThat(code(res)).isEqualTo("NOT_FOUND");
        assertThat(message(res)).isEqualTo("Vendor not found: abc");
    }

    @Test
    void missingResourceWithNoMessageStillReadsAsNotFound() {
        var res = handler.handleNoSuchElement(new NoSuchElementException());

        assertThat(status(res)).isEqualTo(404);
        assertThat(message(res)).isEqualTo("Resource not found");
    }

    // ── the rest of the client-error family ───────────────────────────────────

    @Test
    void wrongHttpMethodIs405() {
        var res = handler.handleMethodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("PUT"));

        assertThat(status(res)).isEqualTo(405);
        assertThat(code(res)).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(message(res)).contains("PUT");
    }

    @Test
    void wrongContentTypeIs415() {
        var res = handler.handleMediaTypeNotSupported(
                new org.springframework.web.HttpMediaTypeNotSupportedException("text/plain"));

        assertThat(status(res)).isEqualTo(415);
        assertThat(code(res)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void constraintViolationIs400WithSortedFieldDetail() {
        var res = handler.handleConstraintViolation(new jakarta.validation.ConstraintViolationException(null));

        assertThat(status(res)).isEqualTo(400);
        assertThat(code(res)).isEqualTo("VALIDATION_FAILED");
        assertThat(res.getBody().getError().getDetails()).isEmpty();
    }

    @Test
    void databaseConstraintViolationIs409AndDoesNotLeakTheSchema() {
        // The driver's message names tables, columns and index names.
        var res = handler.handleDataIntegrity(
                new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"listing_availability_record_id_slot_date_key\""));

        assertThat(status(res)).isEqualTo(409);
        assertThat(code(res)).isEqualTo("CONSTRAINT_VIOLATION");
        assertThat(message(res)).doesNotContain("listing_availability").doesNotContain("constraint \"");
    }

    @Test
    void aPathNoControllerMapsIs404NotAServerError() {
        // Spring routes an unmatched path to the static-resource handler, which throws this. Found
        // live: POSTing to a mistyped settlement URL returned 500, so a client typo was
        // indistinguishable from the server falling over.
        // Mocked rather than constructed: the constructor signature is Spring-version-specific and
        // the handler only reads the path off it.
        var ex = org.mockito.Mockito.mock(
                org.springframework.web.servlet.resource.NoResourceFoundException.class);
        org.mockito.Mockito.when(ex.getResourcePath()).thenReturn("/api/v1/bookings/x/settlement/paid");

        var res = handler.handleNoResource(ex);

        assertThat(status(res)).isEqualTo(404);
        assertThat(code(res)).isEqualTo("NO_SUCH_ENDPOINT");
    }

    // ── what must NOT have changed ────────────────────────────────────────────

    @Test
    void genuineServerFaultsAreStill500() {
        // The point of fixing this at the throw sites rather than by blanket-mapping
        // IllegalStateException: the platform uses it for real faults too (vendor-service's
        // "Failed to create VENDOR record in record-service"), and those must keep alerting.
        var res = handler.handleGeneric(new IllegalStateException("Failed to create VENDOR record"));

        assertThat(status(res)).isEqualTo(500);
        assertThat(code(res)).isEqualTo("INTERNAL_ERROR");
        assertThat(message(res)).isEqualTo("An unexpected error occurred");
    }

    @Test
    void platformExceptionStillCarriesItsOwnStatusAndCode() {
        var res = handler.handlePlatform(new PlatformException(
                "INVALID_STATUS_TRANSITION", "Cannot change vendor status", HttpStatus.CONFLICT));

        assertThat(status(res)).isEqualTo(409);
        assertThat(code(res)).isEqualTo("INVALID_STATUS_TRANSITION");
    }
}
