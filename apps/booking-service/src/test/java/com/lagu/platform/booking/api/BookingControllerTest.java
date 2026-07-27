package com.lagu.platform.booking.api;

import com.lagu.platform.booking.dto.BookingResponse;
import com.lagu.platform.booking.dto.CreateBookingRequest;
import com.lagu.platform.booking.service.BookingService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingControllerTest {

    private final BookingService bookingService = mock(BookingService.class);
    private final BookingController controller = new BookingController(bookingService);

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    private void asCaller(PlatformSecurityContext ctx) {
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(ctx);
    }

    @AfterEach
    void tearDown() {
        if (gatewayMock != null) gatewayMock.close();
    }

    private static PlatformSecurityContext consumerCtx(UUID userId) {
        return PlatformSecurityContext.builder().userId(userId).roles(Set.of("USER")).build();
    }

    private static BookingResponse sampleResponse() {
        return BookingResponse.builder()
                .id(UUID.randomUUID())
                .status("INQUIRY")
                .build();
    }

    @Test
    void createRequiresAuthentication() {
        asCaller(null);
        CreateBookingRequest req = new CreateBookingRequest(UUID.randomUUID(), LocalDate.now().plusDays(1), null, null);

        assertThatThrownBy(() -> controller.create(req)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void createSucceedsForAuthenticatedConsumer() {
        UUID userId = UUID.randomUUID();
        asCaller(consumerCtx(userId));
        CreateBookingRequest req = new CreateBookingRequest(UUID.randomUUID(), LocalDate.now().plusDays(1), null, null);
        when(bookingService.create(req, userId)).thenReturn(sampleResponse());

        var resp = controller.create(req);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void getPassesNullableTenantIdThrough() {
        UUID userId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        asCaller(consumerCtx(userId));
        when(bookingService.get(bookingId, userId, null)).thenReturn(sampleResponse());

        var resp = controller.get(bookingId);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void listMineRequiresAuthentication() {
        asCaller(null);
        assertThatThrownBy(() -> controller.listMine(null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listVendorDelegatesTenantIdEvenWhenNull() {
        asCaller(consumerCtx(UUID.randomUUID()));
        when(bookingService.listVendor(isNull())).thenThrow(
                new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> controller.listVendor()).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cancelDefaultsMissingBodyToNullReason() {
        UUID userId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        asCaller(consumerCtx(userId));
        when(bookingService.cancel(any(), any(), any(), any())).thenReturn(sampleResponse());

        var resp = controller.cancel(bookingId, null);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
