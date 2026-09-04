package com.lagu.platform.listing.service;

import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.listing.client.SchemaRegistryClient;
import com.lagu.platform.listing.client.VendorServiceClient;
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
    private final VendorServiceClient vendorServiceClient;

    /** Approved by its own workflow, withheld because the vendor org has not passed KYC.
     *  Not PUBLISHED, so consumer reads and search never see it. */
    public static final String PENDING_VENDOR_ACTIVATION = "PENDING_VENDOR_ACTIVATION";

    /**
     * Called by the Kafka consumer when a record transitions to ACTIVE/APPROVED, and by the
     * admin manual-publish endpoint. searchBoost is always derived from verificationTier here
     * (never accepted as caller input) so a caller cannot set an arbitrary search-ranking boost.
     */
    @Transactional
    public ListingSnapshot publishSnapshot(UUID recordId, UUID tenantId, String objectType,
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
        snap.setTenantId(tenantId);
        snap.setObjectType(objectType.toUpperCase());
        snap.setData(recordData != null ? recordData : Map.of());
        snap.setVerificationTier(tier);
        snap.setSearchBoost(boostForTier(tier));
        // version is now a real @Version column — Hibernate increments it, not us.

        // The KYC gate. The record's own workflow says this listing is fit to publish; the vendor
        // org's review status says whether the *business behind it* is. Those were two independent
        // lifecycles and nothing compared them, so a vendor who never completed KYC could be
        // publicly listed and take bookings.
        //
        // Held rather than rejected: the snapshot is still written, at a status the consumer read
        // paths exclude (they allowlist PUBLISHED — see ListingVisibility), and no listing event is
        // emitted so search never indexes it. That leaves a row to reconcile from when the org is
        // activated. Refusing outright would strand the vendor instead: nothing in the platform
        // re-publishes an approved listing after the fact, so their listing would stay invisible
        // forever with no signal to anyone.
        if (!vendorServiceClient.isActive(tenantId)) {
            snap.setStatus(PENDING_VENDOR_ACTIVATION);
            ListingSnapshot held = snapshotRepo.save(snap);
            log.info("Holding snapshot for record {} org {} — vendor org is not ACTIVE; "
                    + "it will publish when the org is activated", recordId, tenantId);
            return held;
        }

        snap.setStatus("PUBLISHED");
        snap.setPublishedAt(Instant.now());

        ListingSnapshot saved = snapshotRepo.save(snap);
        eventPublisher.publishPublished(saved);
        log.info("Published snapshot for record {} org {} type {}", recordId, tenantId, objectType);
        return saved;
    }

    /**
     * Publishes everything an org had held behind the KYC gate. Called by vendor-service the
     * moment the org reaches ACTIVE, which is what stops the gate being a one-way door.
     *
     * <p>Idempotent and safe to re-run: it only touches rows in the held state, so calling it for
     * an org with nothing held, or twice in a row, does nothing the second time. That matters
     * because vendor-service's call is best-effort — re-running this is the recovery path.
     *
     * @return how many snapshots went live
     */
    @Transactional
    public int reconcileHeldSnapshots(UUID tenantId) {
        List<ListingSnapshot> held = snapshotRepo.findByTenantIdAndStatus(tenantId, PENDING_VENDOR_ACTIVATION);
        for (ListingSnapshot snap : held) {
            snap.setStatus("PUBLISHED");
            snap.setPublishedAt(Instant.now());
            eventPublisher.publishPublished(snapshotRepo.save(snap));
        }
        if (!held.isEmpty()) {
            log.info("Released {} held snapshot(s) for org {} after activation", held.size(), tenantId);
        }
        return held.size();
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
            boolean live = "PUBLISHED".equals(snap.getStatus());
            // A KYC-held snapshot is refreshed too, so that whatever the vendor edited while
            // waiting is what goes live on release — otherwise it would publish the data frozen at
            // the moment it was held. No event is emitted for it: it is still not public.
            if (!live && !PENDING_VENDOR_ACTIVATION.equals(snap.getStatus())) return;
            snap.setData(recordData != null ? recordData : Map.of());
            ListingSnapshot saved = snapshotRepo.save(snap);
            if (live) eventPublisher.publishPublished(saved);
            log.info("Refreshed snapshot data for record {} (status {})", recordId, saved.getStatus());
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

    public List<ListingSnapshot> getByOrg(UUID tenantId) {
        return snapshotRepo.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    public Optional<ListingSnapshot> getByRecordId(UUID recordId) {
        return snapshotRepo.findByRecordId(recordId);
    }

    /** Platform-admin listing across every org. Caller must be authorized by
     *  ListingController.requireAdmin() before this is invoked. */
    public PageResult<ListingSnapshot> listForAdmin(String objectType, UUID tenantId, String status,
                                                     String verificationTier, int page, int size) {
        String ot = (objectType != null && !objectType.isBlank()) ? objectType.toUpperCase() : null;
        String st = (status != null && !status.isBlank()) ? status.toUpperCase() : null;
        String tier = (verificationTier != null && !verificationTier.isBlank()) ? verificationTier.toUpperCase() : null;
        return PageResult.from(snapshotRepo.search(ot, tenantId, st, tier, PageRequest.of(page, size)));
    }

    /** Consumer-facing paginated listing search (DB fallback; OpenSearch is the primary path). */
    public List<ListingSnapshot> searchPublished(String objectType, int page, int size) {
        return snapshotRepo.findPublishedByObjectType(
                objectType.toUpperCase(), PageRequest.of(page, size)).getContent();
    }

    // ── Availability ──────────────────────────────────────────────────────────

    @Transactional
    public List<ListingAvailability> setAvailability(UUID recordId, UUID tenantId,
                                                     LocalDate from, LocalDate to, String slotType) {
        ListingSnapshot snap = snapshotRepo.findByRecordId(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("ListingSnapshot", recordId.toString()));
        if (!snap.getTenantId().equals(tenantId)) {
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
            slot.setTenantId(tenantId);
            slot.setSlotDate(date);
            slot.setSlotType(slotType.toUpperCase());
            saved.add(availabilityRepo.save(slot));
        }
        return saved;
    }

    /**
     * Explicitly-recorded slots in the range. A date with no row is not "unavailable" — it is
     * simply unrecorded, and is claimable. See {@link ListingAvailabilityRepository#claimSlot}.
     * A calendar UI should render an absent date as open, not as blocked.
     */
    public List<ListingAvailability> getAvailability(UUID recordId, LocalDate from, LocalDate to) {
        return availabilityRepo.findByRecordIdAndSlotDateBetween(recordId, from, to);
    }

    /**
     * Records what consumers have said about a listing, and republishes it so the marketplace sees.
     *
     * <p>booking-service owns the reviews — it is the only service that knows whether a reviewer
     * actually bought anything — and hands the aggregate here because the snapshot is what becomes
     * a {@code ListingEvent} and therefore what search-service indexes. A rating that never
     * reaches the snapshot cannot appear on a card without the results grid fetching it per tile,
     * which is twenty round trips for one page.
     *
     * <p>Republished only for a live listing: an unpublished snapshot has nothing in the consumer
     * index to correct, and pushing a PUBLISHED event for one would put it back on the marketplace
     * on the strength of somebody leaving a review.
     *
     * @param average null when nothing verified has been said, which is distinct from a low score
     * @return false when there is no snapshot to update, so the caller can tell "no such listing"
     *     from "nothing to do"
     */
    @Transactional
    public boolean applyRating(UUID recordId, BigDecimal average, int reviewCount) {
        var found = snapshotRepo.findByRecordId(recordId);
        if (found.isEmpty()) return false;

        ListingSnapshot snap = found.get();
        snap.setRatingAverage(average);
        snap.setReviewCount(reviewCount);
        ListingSnapshot saved = snapshotRepo.save(snap);

        if ("PUBLISHED".equals(saved.getStatus())) {
            eventPublisher.publishPublished(saved);
        }
        return true;
    }

    /**
     * Claims one day for a booking. True if this call took the day, false if it was already
     * BOOKED or the vendor had BLOCKED it.
     *
     * <p>The tenant comes from the listing snapshot rather than the caller: booking-service claims
     * on the vendor's behalf under its own internal-service identity and has no business asserting
     * whose org the slot belongs to. A listing with no snapshot cannot be booked at all, so a
     * missing one fails closed rather than inventing a tenant for the new row.
     */
    @Transactional
    public boolean bookSlot(UUID recordId, LocalDate date, UUID bookingRef) {
        UUID tenantId = snapshotRepo.findByRecordId(recordId)
                .map(ListingSnapshot::getTenantId)
                .orElse(null);
        if (tenantId == null) {
            log.warn("Refusing to claim {} on {}: no listing snapshot for that record", recordId, date);
            return false;
        }
        boolean claimed = availabilityRepo.claimSlot(recordId, tenantId, date, bookingRef) > 0;
        if (claimed) republishAvailability(recordId);
        return claimed;
    }

    /** Inverse of {@link #bookSlot}: only releases a slot this exact bookingRef claimed. */
    @Transactional
    public boolean releaseSlot(UUID recordId, LocalDate date, UUID bookingRef) {
        boolean released = availabilityRepo.releaseBooked(recordId, date, bookingRef) > 0;
        if (released) republishAvailability(recordId);
        return released;
    }

    /**
     * Puts a listing back on the wire because the days it can take have changed.
     *
     * <p>Without this the marketplace keeps offering a hall for a date it took an hour ago: the
     * consumer index is only ever written from a `ListingEvent`, and until now nothing published
     * one when availability moved. Only for a live listing — an unpublished snapshot has nothing
     * in the index to correct.
     *
     * <p>Best-effort against the claim itself, which has already committed. A booking must not
     * fail because a search document is briefly stale; the next publish or reconcile catches it.
     */
    private void republishAvailability(UUID recordId) {
        try {
            snapshotRepo.findByRecordId(recordId)
                    .filter(snap -> "PUBLISHED".equals(snap.getStatus()))
                    .ifPresent(eventPublisher::publishPublished);
        } catch (Exception e) {
            log.warn("Could not republish {} after an availability change — the marketplace may "
                    + "offer a taken date until the next publish: {}", recordId, e.getMessage());
        }
    }
}
