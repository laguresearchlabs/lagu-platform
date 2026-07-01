package com.lagu.platform.listing.service;

import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.domain.ListingSnapshotRepository;
import com.lagu.platform.listing.domain.ListingAvailability;
import com.lagu.platform.listing.domain.ListingAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ListingSnapshotService {

    private static final Set<String> VENDOR_LISTING_TYPES = Set.of(
            "VENUE", "PHOTOGRAPHER", "CATERER", "DECORATOR", "MAKEUP_ARTIST");

    private final ListingSnapshotRepository snapshotRepo;
    private final ListingAvailabilityRepository availabilityRepo;

    /**
     * Called by the Kafka consumer when a record transitions to ACTIVE/APPROVED.
     * Creates or updates the consumer-facing snapshot.
     */
    @Transactional
    public ListingSnapshot publishSnapshot(UUID recordId, UUID orgId, String objectType,
                                           Map<String, Object> recordData,
                                           String verificationTier, BigDecimal searchBoost) {
        if (!VENDOR_LISTING_TYPES.contains(objectType.toUpperCase())) {
            log.debug("Skipping snapshot for non-listing objectType: {}", objectType);
            return null;
        }

        ListingSnapshot snap = snapshotRepo.findByRecordId(recordId)
                .orElseGet(ListingSnapshot::new);

        snap.setRecordId(recordId);
        snap.setOrgId(orgId);
        snap.setObjectType(objectType.toUpperCase());
        snap.setData(recordData != null ? recordData : Map.of());
        snap.setStatus("PUBLISHED");
        snap.setVerificationTier(verificationTier != null ? verificationTier : "NONE");
        snap.setSearchBoost(searchBoost != null ? searchBoost : BigDecimal.ONE);
        snap.setPublishedAt(Instant.now());
        snap.setVersion(snap.getVersion() + 1);

        ListingSnapshot saved = snapshotRepo.save(snap);
        log.info("Published snapshot for record {} org {} type {}", recordId, orgId, objectType);
        return saved;
    }

    /** Depublish when listing is suspended/archived. */
    @Transactional
    public void unpublishSnapshot(UUID recordId) {
        snapshotRepo.findByRecordId(recordId).ifPresent(snap -> {
            snap.setStatus("UNPUBLISHED");
            snapshotRepo.save(snap);
            log.info("Unpublished snapshot for record {}", recordId);
        });
    }

    public List<ListingSnapshot> getByOrg(UUID orgId) {
        return snapshotRepo.findByOrgIdOrderByUpdatedAtDesc(orgId);
    }

    public Optional<ListingSnapshot> getByRecordId(UUID recordId) {
        return snapshotRepo.findByRecordId(recordId);
    }

    /** Consumer-facing paginated listing search (DB fallback; OpenSearch is the primary path). */
    public List<ListingSnapshot> searchPublished(String objectType, int page, int size) {
        return snapshotRepo.findPublishedByObjectType(
                objectType.toUpperCase(), PageRequest.of(page, size)).getContent();
    }

    // ── Availability ──────────────────────────────────────────────────────────

    @Transactional
    public List<ListingAvailability> setAvailability(UUID recordId, UUID orgId,
                                                     LocalDate from, LocalDate to, String slotType) {
        List<LocalDate> dates = from.datesUntil(to.plusDays(1)).toList();
        List<ListingAvailability> saved = new ArrayList<>();
        for (LocalDate date : dates) {
            ListingAvailability slot = availabilityRepo
                    .findByRecordIdAndSlotDate(recordId, date)
                    .orElseGet(ListingAvailability::new);
            slot.setRecordId(recordId);
            slot.setOrgId(orgId);
            slot.setSlotDate(date);
            slot.setSlotType(slotType.toUpperCase());
            saved.add(availabilityRepo.save(slot));
        }
        return saved;
    }

    public List<ListingAvailability> getAvailability(UUID recordId, LocalDate from, LocalDate to) {
        return availabilityRepo.findByRecordIdAndSlotDateBetween(recordId, from, to);
    }

    @Transactional
    public boolean bookSlot(UUID recordId, LocalDate date, UUID bookingRef) {
        return availabilityRepo.markBooked(recordId, date, bookingRef) > 0;
    }
}
