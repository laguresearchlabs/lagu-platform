package com.lagu.platform.vendor.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
import com.lagu.platform.vendor.dto.MembershipOwnerResponse;
import com.lagu.platform.vendor.service.VendorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner lookup is the only thing standing between booking-service and "notify the vendor", so
 * its two failure shapes matter as much as the success: it must stay closed to external callers,
 * and it must 404 rather than guess when an org has no active owner.
 */
class InternalMembershipControllerTest {

    private final VendorMemberRepository memberRepo = mock(VendorMemberRepository.class);
    private final VendorService vendorService = mock(VendorService.class);
    private final InternalMembershipController controller =
            new InternalMembershipController(memberRepo, vendorService);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerUserId = UUID.randomUUID();

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    private void asCaller(PlatformSecurityContext ctx) {
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(ctx);
    }

    @AfterEach
    void tearDown() {
        if (gatewayMock != null) gatewayMock.close();
    }

    /** An internal caller is one carrying a SVC_* role, which GatewayHeaderFilter grants only
     *  against X-Internal-Service plus the shared secret — there is no separate flag. */
    private static PlatformSecurityContext internalCtx() {
        return PlatformSecurityContext.builder()
                .roles(Set.of(GatewayHeaderFilter.SERVICE_ROLE_PREFIX + "BOOKING_SERVICE"))
                .build();
    }

    private void stubTarget(Optional<MembershipOwnerResponse> result) {
        when(vendorService.resolveNotificationTarget(tenantId)).thenReturn(result);
    }

    @Test
    void returnsTheActiveOwnerForAnInternalCaller() {
        asCaller(internalCtx());
        stubTarget(Optional.of(new MembershipOwnerResponse(ownerUserId, "OWNER", "bookings@venue.example")));

        ResponseEntity<ApiResponse<MembershipOwnerResponse>> res = controller.getOwner(tenantId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getData().getUserId()).isEqualTo(ownerUserId);
        assertThat(res.getBody().getData().getRole()).isEqualTo("OWNER");
        assertThat(res.getBody().getData().getContactEmail()).isEqualTo("bookings@venue.example");
    }

    @Test
    void returns404WhenTheOrgHasNoActiveOwner() {
        // A removed owner must not resolve to some other member by accident — booking-service
        // treats an empty answer as "no recipient" and skips the notification, which is correct.
        asCaller(internalCtx());
        stubTarget(Optional.empty());

        assertThat(controller.getOwner(tenantId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesAnExternalCaller() {
        // The route lives under /internal/**, which the gateway denies outright — this is the
        // second lock, for anything reaching the pod directly inside the cluster.
        asCaller(PlatformSecurityContext.builder().userId(UUID.randomUUID()).roles(Set.of("USER")).build());

        assertThatThrownBy(() -> controller.getOwner(tenantId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Internal callers only");
    }

    @Test
    void refusesAnUnauthenticatedCaller() {
        asCaller(null);

        assertThatThrownBy(() -> controller.getOwner(tenantId))
                .isInstanceOf(ResponseStatusException.class);
    }
}
