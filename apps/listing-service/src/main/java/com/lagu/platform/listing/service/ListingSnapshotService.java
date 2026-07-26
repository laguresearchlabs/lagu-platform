package com.lagu.platform.listing.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.listing.client.SchemaRegistryClient;
import com.lagu.platform.listing.event.ListingEventPublisher;
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

    private final ListingSnapshotRepository snapshotRepo;
    private final ListingAvailabilityRepository availabilityRepo;
    private final ListingEventPublisher eventPublisher;
    private final SchemaRegistryClient schemaRegistryClient;

    /**
     * Called by the Kafka consumer when a record transitions to ACTIVE/APPROVED, and by the
     * admin manual-publish endpoint. searchBoost is always derived from verificationTier here
     * (never accepted as caller input) so a caller cannot set an arbitrary search-ranking boost.
     */
    @Transactional
    public ListingSnapshot publishSnapshot(UUID recordId, UUID orgId, String objectType,
                                           Map<String, Object> recordData,
                                           String verificationTier) {
        // Source of truth is schema-registry's own ListingTypeDefinition.publishable/
        // consumerSearchable flags — previously a hardcoded allowlist here, which drifted out
        // of sync (event record types were never added to it despite having their own
        // workflows). consumerSearchable was fetched but never actually checked: a listing type
        // marked publishable=true but consumerSearchable=false was snapshotted and pushed into
        // the public consumer search index anyway.
        var flags = schemaRegistryClient.getFlags(objectType);
        if (!flags.publishable() || !flags.consumerSearchable()) {
            log.debug("Skipping snapshot for objectType {} (publishable={}, consumerSearchable={})",
                    objectType, flags.publishable(), flags.consumerSearchable());
            return null;
        }

        String tier = verificationTier != null ? verificationTier : "NONE";

        ListingSnapshot snap = snapshotRepo.findByRecordId(recordId)
                .orElseGet(ListingSnapshot::new);

        snap.setRecordId(recordId);
        snap.setOrgId(orgId);
        snap.setObjectType(objectType.toUpperCase());
        snap.setData(recordData != null ? recordData : Map.of());
        snap.setStatus("PUBLISHED");
        snap.setVerificationTier(tier);
        snap.setSearchBoost(boostForTier(tier));
        snap.setPublishedAt(Instant.now());
        // version is now a real @Version column — Hibernate increments it, not us.

        ListingSnapshot saved = snapshotRepo.save(snap);
        eventPublisher.publishPublished(saved);
        log.info("Published snapshot for record {} org {} type {}", recordId, orgId, objectType);
        return saved;
    }

    /**
     * Mirrors the searchBoostFactor values seeded into schema-registry's TierConfiguration
     * (NONE 1.0 / BASIC 1.5 / ENHANCED 1.8 / PREMIUM 2.0). If the tier ladder changes there,
     * this must follow — longer term this should be fetched from schema-registry instead.
     */
    public static BigDecimal boostForTier(String tier) {
        return switch (tier) {
            case "BASIC"    -> new BigDecimal("1.5");
            case "ENHANCED" -> new BigDecimal("1.8");
            case "PREMIUM"  -> new BigDecimal("2.0");
            default         -> BigDecimal.ONE;
        };
    }

    /**
     * Refreshes a snapshot's data when the source record is edited without a workflow
     * transition (e.g. a vendor updates their listing's price/address/phone while it's already
     * ACTIVE). Previously nothing consumed RecordEvent at all in this service — only
     * WorkflowEvent TRANSITIONED — so an edit to an already-published listing never reached the
     * snapshot table or the public search index; the marketplace served stale data indefinitely.
     * A no-op if the record has never been published (nothing to refresh) or is currently
     * UNPUBLISHED/SUSPENDED/etc — an edit must not silently re-publish something the workflow
     * took down.
     */
    @Transactional
    public void refreshSnapshotData(UUID recordId, Map<String, Object> recordData) {
        snapshotRepo.findByRecordId(recordId).ifPresent(snap -> {
            if (!"PUBLISHED".equals(snap.getStatus())) return;
            snap.setData(recordData != null ? recordData : Map.of());
            ListingSnapshot saved = snapshotRepo.save(snap);
            eventPublisher.publishPublished(saved);
            log.info("Refreshed snapshot data for record {}", recordId);
        });
    }

    /** Depublish when listing is suspended/archived/deleted. */
    @Transactional
    public void unpublishSnapshot(UUID recordId) {
        snapshotRepo.findByRecordId(recordId).ifPresent(snap -> {
            snap.setStatus("UNPUBLISHED");
            snapshotRepo.save(snap);
            eventPublisher.publishUnpublished(snap);
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
        ListingSnapshot snap = snapshotRepo.findByRecordId(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("ListingSnapshot", recordId.toString()));
        if (!snap.getOrgId().equals(orgId)) {
            // Caller's org doesn't own this record — treat as not found rather than leaking existence.
            throw new ResourceNotFoundException("ListingSnapshot", recordId.toString());
        }

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
