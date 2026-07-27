package com.lagu.platform.listing.api;

import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.service.ListingSnapshotService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the review's finding: getSnapshot() had no org or status check at
 * all — any authenticated user of any tenant could read a suspended vendor's (or any
 * not-yet-published) listing snapshot by recordId.
 */
class ListingControllerTest {

    private final ListingSnapshotService snapshotService = mock(ListingSnapshotService.class);
    private final ListingController controller = new ListingController(snapshotService);

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    private void asCaller(PlatformSecurityContext ctx) {
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(ctx);
    }

    @AfterEach
    void tearDown() {
        if (gatewayMock != null) gatewayMock.close();
    }

    private static ListingSnapshot snapshot(UUID tenantId, String status) {
        ListingSnapshot s = new ListingSnapshot();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setStatus(status);
        return s;
    }

    private static PlatformSecurityContext ctx(UUID tenantId, String... roles) {
        return PlatformSecurityContext.builder().userId(UUID.randomUUID()).tenantId(tenantId)
                .roles(Set.of(roles)).build();
    }

    @Test
    void publishedSnapshotIsVisibleToAnyone() {
        UUID recordId = UUID.randomUUID();
        when(snapshotService.getByRecordId(recordId))
                .thenReturn(Optional.of(snapshot(UUID.randomUUID(), "PUBLISHED")));
        asCaller(ctx(UUID.randomUUID())); // a completely different org, no special role

        var resp = controller.getSnapshot(recordId);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void unpublishedSnapshotIsNotVisibleToAnotherOrg() {
        UUID recordId = UUID.randomUUID();
        UUID ownerOrg = UUID.randomUUID();
        when(snapshotService.getByRecordId(recordId))
                .thenReturn(Optional.of(snapshot(ownerOrg, "UNPUBLISHED")));
        asCaller(ctx(UUID.randomUUID())); // different org

        var resp = controller.getSnapshot(recordId);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void unpublishedSnapshotIsVisibleToOwningOrg() {
        UUID recordId = UUID.randomUUID();
        UUID ownerOrg = UUID.randomUUID();
        when(snapshotService.getByRecordId(recordId))
                .thenReturn(Optional.of(snapshot(ownerOrg, "UNPUBLISHED")));
        asCaller(ctx(ownerOrg));

        var resp = controller.getSnapshot(recordId);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void unpublishedSnapshotIsVisibleToPlatformAdmin() {
        UUID recordId = UUID.randomUUID();
        when(snapshotService.getByRecordId(recordId))
                .thenReturn(Optional.of(snapshot(UUID.randomUUID(), "SUSPENDED")));
        asCaller(ctx(UUID.randomUUID(), "PLATFORM_ADMIN"));

        var resp = controller.getSnapshot(recordId);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void unauthenticatedCallerCannotSeeNonPublishedSnapshot() {
        UUID recordId = UUID.randomUUID();
        when(snapshotService.getByRecordId(recordId))
                .thenReturn(Optional.of(snapshot(UUID.randomUUID(), "UNPUBLISHED")));
        asCaller(null);

        var resp = controller.getSnapshot(recordId);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }
}
