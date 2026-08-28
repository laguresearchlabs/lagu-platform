package com.lagu.platform.listing.service;

import com.lagu.platform.listing.client.SchemaRegistryClient;
import com.lagu.platform.listing.client.SchemaRegistryClient.ListingTypeFlags;
import com.lagu.platform.listing.client.VendorServiceClient;
import com.lagu.platform.listing.domain.ListingAvailabilityRepository;
import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.domain.ListingSnapshotRepository;
import com.lagu.platform.listing.event.ListingEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.lagu.platform.listing.service.ListingSnapshotService.PENDING_VENDOR_ACTIVATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The KYC gate: a listing may be approved by its own workflow while the business behind it has
 * never passed KYC. Those were two independent lifecycles and nothing compared them, so an
 * unverified vendor could be publicly listed and take bookings.
 *
 * <p>The subtle half is what happens *after*. Holding rather than rejecting is what stops the gate
 * being a one-way door — nothing in the platform re-publishes an approved listing, so a hard
 * refusal would leave a vendor who later completed KYC permanently invisible with no signal to
 * anyone. These tests pin both directions.
 */
class KycPublishGateTest {

    private final ListingSnapshotRepository snapshotRepo = mock(ListingSnapshotRepository.class);
    private final ListingAvailabilityRepository availabilityRepo = mock(ListingAvailabilityRepository.class);
    private final ListingEventPublisher eventPublisher = mock(ListingEventPublisher.class);
    private final SchemaRegistryClient schemaRegistryClient = mock(SchemaRegistryClient.class);
    private final VendorServiceClient vendorServiceClient = mock(VendorServiceClient.class);

    private final ListingSnapshotService service = new ListingSnapshotService(
            snapshotRepo, availabilityRepo, eventPublisher, schemaRegistryClient, vendorServiceClient);

    private final UUID recordId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(schemaRegistryClient.getFlags(any())).thenReturn(new ListingTypeFlags(true, true));
        when(snapshotRepo.findByRecordId(recordId)).thenReturn(Optional.empty());
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ListingSnapshot publish() {
        return service.publishSnapshot(recordId, tenantId, "VENUE", Map.of("name", "Hall"), "BASIC");
    }

    // ── the gate ──────────────────────────────────────────────────────────────

    @Test
    void anActiveVendorPublishesNormally() {
        when(vendorServiceClient.isActive(tenantId)).thenReturn(true);

        ListingSnapshot snap = publish();

        assertThat(snap.getStatus()).isEqualTo("PUBLISHED");
        assertThat(snap.getPublishedAt()).isNotNull();
        verify(eventPublisher).publishPublished(snap);
    }

    @Test
    void aVendorThatIsNotActiveIsHeldRatherThanPublished() {
        when(vendorServiceClient.isActive(tenantId)).thenReturn(false);

        ListingSnapshot snap = publish();

        assertThat(snap.getStatus()).isEqualTo(PENDING_VENDOR_ACTIVATION);
    }

    @Test
    void aHeldSnapshotEmitsNoEventSoSearchNeverIndexesIt() {
        // The snapshot row exists but must not reach the consumer search index — an event here
        // would put an unverified vendor in front of customers by the back door.
        when(vendorServiceClient.isActive(tenantId)).thenReturn(false);

        publish();

        verify(eventPublisher, never()).publishPublished(any());
    }

    @Test
    void releaseStampsPublishedAtWhenItActuallyGoesLiveNotWhenItWasHeld() {
        // published_at is NOT NULL in the schema and the entity defaults it at construction, so it
        // cannot be used to tell a held row from a live one — `status` is the only source of truth
        // for that. What must hold is that releasing re-stamps it, so the column means "went live
        // at" rather than "was first written at".
        ListingSnapshot heldSnap = held();
        heldSnap.setPublishedAt(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        when(snapshotRepo.findByTenantIdAndStatus(tenantId, PENDING_VENDOR_ACTIVATION))
                .thenReturn(List.of(heldSnap));

        service.reconcileHeldSnapshots(tenantId);

        assertThat(heldSnap.getPublishedAt()).isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void anUnknownVendorStatusHoldsRatherThanPublishes() {
        // VendorServiceClient fails closed. A control that opens when the dependency is down is
        // not a control — and holding costs nothing, because activation reconciles it.
        when(vendorServiceClient.isActive(tenantId)).thenReturn(false);

        assertThat(publish().getStatus()).isEqualTo(PENDING_VENDOR_ACTIVATION);
    }

    @Test
    void theGateRunsAfterTheListingTypeCheckNotInsteadOfIt() {
        // A non-publishable type must still short-circuit to null without ever asking about the
        // vendor — otherwise every event record type would generate a pointless vendor lookup.
        when(schemaRegistryClient.getFlags(any())).thenReturn(new ListingTypeFlags(false, false));

        assertThat(publish()).isNull();
        verify(vendorServiceClient, never()).isActive(any());
    }

    // ── the release ───────────────────────────────────────────────────────────

    @Test
    void activationReleasesEveryHeldSnapshotForTheOrg() {
        ListingSnapshot a = held();
        ListingSnapshot b = held();
        when(snapshotRepo.findByTenantIdAndStatus(tenantId, PENDING_VENDOR_ACTIVATION))
                .thenReturn(List.of(a, b));

        assertThat(service.reconcileHeldSnapshots(tenantId)).isEqualTo(2);

        assertThat(a.getStatus()).isEqualTo("PUBLISHED");
        assertThat(b.getStatus()).isEqualTo("PUBLISHED");
        assertThat(a.getPublishedAt()).isNotNull();
        verify(eventPublisher).publishPublished(a);
        verify(eventPublisher).publishPublished(b);
    }

    @Test
    void reconcileIsIdempotentAndSafeToReRun() {
        // vendor-service's call is best-effort, so re-running this is the documented recovery.
        when(snapshotRepo.findByTenantIdAndStatus(tenantId, PENDING_VENDOR_ACTIVATION))
                .thenReturn(List.of());

        assertThat(service.reconcileHeldSnapshots(tenantId)).isZero();
        verify(eventPublisher, never()).publishPublished(any());
    }

    @Test
    void reconcileOnlyTouchesHeldRowsNotUnpublishedOnes() {
        // A listing the workflow deliberately took down must not come back just because the org
        // was activated — it is queried by the held status alone.
        service.reconcileHeldSnapshots(tenantId);

        verify(snapshotRepo).findByTenantIdAndStatus(tenantId, PENDING_VENDOR_ACTIVATION);
    }

    private ListingSnapshot held() {
        ListingSnapshot s = new ListingSnapshot();
        s.setRecordId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setObjectType("VENUE");
        s.setStatus(PENDING_VENDOR_ACTIVATION);
        return s;
    }
}
