package com.lagu.platform.listing.service;

import com.lagu.platform.listing.client.SchemaRegistryClient;
import com.lagu.platform.listing.client.SchemaRegistryClient.ListingTypeFlags;
import com.lagu.platform.listing.domain.ListingAvailabilityRepository;
import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.domain.ListingSnapshotRepository;
import com.lagu.platform.listing.event.ListingEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * listing-service's first tests — this service previously had none at all. Covers the review's
 * findings that were still live in this class: consumerSearchable was fetched from
 * schema-registry but never actually checked, and there was no path at all for refreshing an
 * already-published snapshot's data short of a full republish through the workflow.
 */
class ListingSnapshotServiceTest {

    private final ListingSnapshotRepository snapshotRepo = mock(ListingSnapshotRepository.class);
    private final ListingAvailabilityRepository availabilityRepo = mock(ListingAvailabilityRepository.class);
    private final ListingEventPublisher eventPublisher = mock(ListingEventPublisher.class);
    private final SchemaRegistryClient schemaRegistryClient = mock(SchemaRegistryClient.class);

    private final ListingSnapshotService service = new ListingSnapshotService(
            snapshotRepo, availabilityRepo, eventPublisher, schemaRegistryClient);

    private static ListingSnapshot published(UUID recordId, UUID tenantId) {
        ListingSnapshot s = new ListingSnapshot();
        s.setRecordId(recordId);
        s.setTenantId(tenantId);
        s.setObjectType("VENUE");
        s.setStatus("PUBLISHED");
        s.setData(Map.of("name", "Old Name"));
        return s;
    }

    // ---- publishSnapshot: publishable/consumerSearchable gating ----

    @Test
    void skipsWhenNotPublishable() {
        when(schemaRegistryClient.getFlags("VENUE")).thenReturn(new ListingTypeFlags(false, true));

        ListingSnapshot result = service.publishSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), "VENUE", Map.of("name", "X"), "NONE");

        assertThat(result).isNull();
        verify(snapshotRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void skipsWhenPublishableButNotConsumerSearchable() {
        // This is the exact bug: previously only .publishable() was checked, so a listing type
        // marked publishable=true/consumerSearchable=false was snapshotted and pushed into the
        // public consumer search index anyway.
        when(schemaRegistryClient.getFlags("VENUE")).thenReturn(new ListingTypeFlags(true, false));

        ListingSnapshot result = service.publishSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), "VENUE", Map.of("name", "X"), "NONE");

        assertThat(result).isNull();
        verify(snapshotRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void publishesWhenBothFlagsTrue() {
        UUID recordId = UUID.randomUUID();
        when(schemaRegistryClient.getFlags("VENUE")).thenReturn(new ListingTypeFlags(true, true));
        when(snapshotRepo.findByRecordId(recordId)).thenReturn(Optional.empty());
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ListingSnapshot result = service.publishSnapshot(
                recordId, UUID.randomUUID(), "VENUE", Map.of("name", "X"), "BASIC");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PUBLISHED");
        assertThat(result.getSearchBoost()).isEqualByComparingTo("1.5"); // BASIC tier boost
        verify(eventPublisher).publishPublished(result);
    }

    // ---- refreshSnapshotData: the missing RecordEvent UPDATED handling ----

    @Test
    void refreshDataNoOpsWhenNoSnapshotExists() {
        UUID recordId = UUID.randomUUID();
        when(snapshotRepo.findByRecordId(recordId)).thenReturn(Optional.empty());

        service.refreshSnapshotData(recordId, Map.of("name", "New Name"));

        verify(snapshotRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void refreshDataNoOpsWhenSnapshotIsNotCurrentlyPublished() {
        // An edit to a suspended/unpublished listing must not silently re-publish it.
        UUID recordId = UUID.randomUUID();
        ListingSnapshot unpublished = published(recordId, UUID.randomUUID());
        unpublished.setStatus("UNPUBLISHED");
        when(snapshotRepo.findByRecordId(recordId)).thenReturn(Optional.of(unpublished));

        service.refreshSnapshotData(recordId, Map.of("name", "New Name"));

        verify(snapshotRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void refreshDataUpdatesAndRepublishesWhenCurrentlyPublished() {
        UUID recordId = UUID.randomUUID();
        ListingSnapshot existing = published(recordId, UUID.randomUUID());
        when(snapshotRepo.findByRecordId(recordId)).thenReturn(Optional.of(existing));
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refreshSnapshotData(recordId, Map.of("name", "New Name", "price", 500));

        assertThat(existing.getData()).containsEntry("name", "New Name").containsEntry("price", 500);
        verify(eventPublisher).publishPublished(existing);
    }

    // ---- unpublishSnapshot ----

    @Test
    void unpublishSetsStatusAndPublishesEvent() {
        UUID recordId = UUID.randomUUID();
        ListingSnapshot existing = published(recordId, UUID.randomUUID());
        when(snapshotRepo.findByRecordId(recordId)).thenReturn(Optional.of(existing));
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.unpublishSnapshot(recordId);

        assertThat(existing.getStatus()).isEqualTo("UNPUBLISHED");
        verify(eventPublisher).publishUnpublished(existing);
    }

    @Test
    void unpublishNoOpsWhenNoSnapshotExists() {
        UUID recordId = UUID.randomUUID();
        when(snapshotRepo.findByRecordId(recordId)).thenReturn(Optional.empty());

        service.unpublishSnapshot(recordId); // must not throw

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void boostForTierMatchesSchemaRegistrySeededValues() {
        assertThat(ListingSnapshotService.boostForTier("NONE")).isEqualByComparingTo("1");
        assertThat(ListingSnapshotService.boostForTier("BASIC")).isEqualByComparingTo("1.5");
        assertThat(ListingSnapshotService.boostForTier("ENHANCED")).isEqualByComparingTo("1.8");
        assertThat(ListingSnapshotService.boostForTier("PREMIUM")).isEqualByComparingTo("2.0");
    }

    // ---- bookSlot / releaseSlot: the atomic claim primitive booking-service depends on ----

    @Test
    void bookSlotReturnsTrueWhenRowFlipped() {
        UUID recordId = UUID.randomUUID();
        UUID bookingRef = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        when(availabilityRepo.markBooked(recordId, date, bookingRef)).thenReturn(1);

        assertThat(service.bookSlot(recordId, date, bookingRef)).isTrue();
    }

    @Test
    void bookSlotReturnsFalseWhenSlotAlreadyTaken() {
        // The core race-safety property: markBooked's WHERE slotType='AVAILABLE' guard means a
        // second caller trying to book an already-BOOKED (or BLOCKED) slot affects zero rows.
        UUID recordId = UUID.randomUUID();
        UUID bookingRef = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        when(availabilityRepo.markBooked(recordId, date, bookingRef)).thenReturn(0);

        assertThat(service.bookSlot(recordId, date, bookingRef)).isFalse();
    }

    @Test
    void releaseSlotReturnsTrueWhenClaimReleased() {
        UUID recordId = UUID.randomUUID();
        UUID bookingRef = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        when(availabilityRepo.releaseBooked(recordId, date, bookingRef)).thenReturn(1);

        assertThat(service.releaseSlot(recordId, date, bookingRef)).isTrue();
    }

    @Test
    void releaseSlotReturnsFalseWhenBookingRefDoesNotMatch() {
        // Guards against a stale/wrong release clearing someone else's claim.
        UUID recordId = UUID.randomUUID();
        UUID bookingRef = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        when(availabilityRepo.releaseBooked(recordId, date, bookingRef)).thenReturn(0);

        assertThat(service.releaseSlot(recordId, date, bookingRef)).isFalse();
    }
}
