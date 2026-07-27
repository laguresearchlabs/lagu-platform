package com.lagu.platform.listing.api;

import com.lagu.platform.listing.service.ListingSnapshotService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Confirms this endpoint is internal-service-only (booking-service claiming a slot on the
 * vendor's behalf), unlike ListingController's owner-org-gated PUT /availability.
 */
class InternalAvailabilityControllerTest {

    private final ListingSnapshotService snapshotService = mock(ListingSnapshotService.class);
    private final InternalAvailabilityController controller =
            new InternalAvailabilityController(snapshotService);

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    private void asCaller(PlatformSecurityContext ctx) {
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(ctx);
    }

    @AfterEach
    void tearDown() {
        if (gatewayMock != null) gatewayMock.close();
    }

    private static PlatformSecurityContext internalCallerCtx() {
        return PlatformSecurityContext.builder().roles(Set.of("SVC_BOOKING_SERVICE")).build();
    }

    private static PlatformSecurityContext userCtx() {
        return PlatformSecurityContext.builder().userId(UUID.randomUUID())
                .tenantId(UUID.randomUUID()).roles(Set.of("VENDOR")).build();
    }

    @Test
    void bookSucceedsForInternalCaller() {
        UUID recordId = UUID.randomUUID();
        UUID bookingRef = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        when(snapshotService.bookSlot(recordId, date, bookingRef)).thenReturn(true);
        asCaller(internalCallerCtx());

        var resp = controller.book(recordId, date,
                new InternalAvailabilityController.ClaimRequest(bookingRef));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().getData().claimed()).isTrue();
    }

    @Test
    void bookReturnsClaimedFalseWithoutErrorWhenSlotAlreadyTaken() {
        UUID recordId = UUID.randomUUID();
        UUID bookingRef = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        when(snapshotService.bookSlot(recordId, date, bookingRef)).thenReturn(false);
        asCaller(internalCallerCtx());

        var resp = controller.book(recordId, date,
                new InternalAvailabilityController.ClaimRequest(bookingRef));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().getData().claimed()).isFalse();
    }

    @Test
    void bookRejectsNonInternalCaller() {
        UUID recordId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        asCaller(userCtx());

        assertThatThrownBy(() -> controller.book(recordId, date,
                new InternalAvailabilityController.ClaimRequest(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void bookRejectsUnauthenticatedCaller() {
        UUID recordId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        asCaller(null);

        assertThatThrownBy(() -> controller.book(recordId, date,
                new InternalAvailabilityController.ClaimRequest(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void releaseSucceedsForInternalCaller() {
        UUID recordId = UUID.randomUUID();
        UUID bookingRef = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        when(snapshotService.releaseSlot(recordId, date, bookingRef)).thenReturn(true);
        asCaller(internalCallerCtx());

        var resp = controller.release(recordId, date,
                new InternalAvailabilityController.ClaimRequest(bookingRef));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().getData().released()).isTrue();
    }

    @Test
    void releaseRejectsNonInternalCaller() {
        UUID recordId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        asCaller(userCtx());

        assertThatThrownBy(() -> controller.release(recordId, date,
                new InternalAvailabilityController.ClaimRequest(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class);
    }
}
