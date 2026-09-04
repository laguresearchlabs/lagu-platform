package com.lagu.platform.booking.service;

import com.lagu.platform.booking.client.EventServiceClient;
import com.lagu.platform.booking.client.ListingServiceClient;
import com.lagu.platform.booking.client.SchemaRegistryClient;
import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.booking.domain.BookingRepository;
import com.lagu.platform.booking.domain.BookingStatus;
import com.lagu.platform.booking.domain.SettlementStatus;
import com.lagu.platform.booking.dto.BookingResponse;
import com.lagu.platform.booking.dto.CancelBookingRequest;
import com.lagu.platform.booking.dto.CreateBookingRequest;
import com.lagu.platform.booking.dto.QuoteBookingRequest;
import com.lagu.platform.booking.event.BookingEventPublisher;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Booking's own local, synchronous state machine (INQUIRY -> QUOTED -> CONFIRMED ->
 * COMPLETED/CANCELLED). Deliberately not a record-service Record or a workflow-service workflow —
 * see booking-service's README for why (the Confirm step must claim listing-service's
 * availability slot atomically with the local status change, which an async engine is a poor fit
 * for; the state machine is small, fixed, and has asymmetric two-party rules rather than
 * admin-configurable no-code transitions).
 *
 * <p>Methods take plain userId/tenantId params rather than a PlatformSecurityContext, matching
 * EventService/VendorService's convention — keeps this class plain-Mockito-testable with zero
 * Spring context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BookingService {

    private final BookingRepository bookingRepo;
    private final ListingServiceClient listingClient;
    private final EventServiceClient eventClient;
    private final SchemaRegistryClient schemaRegistryClient;
    private final BookingEventPublisher eventPublisher;

    @Transactional
    public BookingResponse create(CreateBookingRequest req, UUID consumerUserId) {
        ListingServiceClient.ListingInfo listing = listingClient.getSnapshot(req.listingRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", req.listingRecordId().toString()));
        if (!"PUBLISHED".equals(listing.status())) {
            throw new PlatformException("LISTING_NOT_BOOKABLE",
                    "This listing is not currently published", HttpStatus.CONFLICT);
        }

        Booking booking = new Booking();
        booking.setConsumerUserId(consumerUserId);
        booking.setVendorId(listing.tenantId());
        booking.setListingRecordId(req.listingRecordId());
        booking.setEventId(req.eventId());
        booking.setEventDate(req.eventDate());
        booking.setGuestCount(req.guestCount());
        booking.setInquiryMessage(req.inquiryMessage());

        boolean shortlisting = Boolean.TRUE.equals(req.shortlist());
        booking.setStatus(shortlisting ? BookingStatus.SHORTLISTED : BookingStatus.INQUIRY);

        Booking saved = bookingRepo.save(booking);

        // Nothing is staged for a shortlist. The outbox is what automation-service turns into a
        // vendor notification, so publishing here would tell a vendor they had been asked about a
        // date by someone who has only bookmarked them — see BookingStatus.SHORTLISTED.
        if (!shortlisting) {
            eventPublisher.publish(saved, "INQUIRED", null, consumerUserId);
        }

        log.info("Booking {} {}: consumer={} listing={} date={} guests={}",
                saved.getId(), shortlisting ? "shortlisted" : "inquired", consumerUserId,
                req.listingRecordId(), req.eventDate(), req.guestCount());
        return BookingResponse.from(saved);
    }

    public BookingResponse get(UUID bookingId, UUID actingUserId, UUID actingTenantId) {
        Booking booking = requireBooking(bookingId);
        requireEitherSide(booking, actingUserId, actingTenantId);
        return BookingResponse.from(booking);
    }

    public List<BookingResponse> listMine(UUID consumerUserId, UUID eventIdFilter) {
        List<Booking> bookings = eventIdFilter != null
                ? bookingRepo.findByConsumerUserIdAndEventIdOrderByCreatedAtDesc(consumerUserId, eventIdFilter)
                : bookingRepo.findByConsumerUserIdOrderByCreatedAtDesc(consumerUserId);
        return bookings.stream().map(BookingResponse::from).toList();
    }

    /**
     * Every inquiry raised for one event, whoever raised it.
     *
     * <p>The counterpart to {@link #listMine}, and the reason it exists: `/mine` filters by
     * consumer, so two co-hosts planning one event each saw only their own inquiries and had no
     * way to tell that the other had already asked the same caterer for a quote. The event's
     * Vendors tab said as much out loud — "inquiries sent by other hosts aren't listed here" —
     * which was honest about a hole rather than a design.
     *
     * <p>Authorised on the event's own membership ladder, which booking-service does not model:
     * it asks event-service and enforces the answer here. A co-host or the organizer may read;
     * anybody else, including a guest of that event, may not — a guest cannot raise an inquiry,
     * so there is nothing here that is theirs.
     */
    public List<BookingResponse> listForEvent(UUID eventId, UUID requesterId) {
        boolean canManage = eventClient.membershipOf(eventId, requesterId)
                .map(EventServiceClient.Membership::canManage)
                .orElse(false);

        if (!canManage) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a host of this event may list its inquiries");
        }

        return bookingRepo.findByEventIdOrderByCreatedAtDesc(eventId).stream()
                .map(BookingResponse::from).toList();
    }

    /**
     * Sends a shortlisted booking, which is the first moment the vendor learns of it.
     *
     * <p>Separate from {@link #create} rather than a status argument on it: this is the point the
     * outbox event is staged and a notification goes out, and that transition deserves its own
     * name and its own authorisation check rather than being a branch inside a constructor.
     */
    @Transactional
    public BookingResponse inquire(UUID bookingId, UUID actingUserId) {
        Booking booking = requireBooking(bookingId);
        // The consumer's own, and only theirs: a vendor cannot promote a shortlist they cannot
        // see, and a co-host acts through their own consumer identity like anyone else.
        if (!booking.getConsumerUserId().equals(actingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the person who saved this listing may send the inquiry");
        }
        requireStatus(booking, BookingStatus.SHORTLISTED);

        String previousStatus = booking.getStatus().name();
        booking.setStatus(BookingStatus.INQUIRY);

        Booking saved = bookingRepo.save(booking);
        eventPublisher.publish(saved, "INQUIRED", previousStatus, actingUserId);
        log.info("Booking {} sent from shortlist: consumer={}", saved.getId(), actingUserId);
        return BookingResponse.from(saved);
    }

    /**
     * Removes a shortlisted booking outright.
     *
     * <p>A delete rather than a cancel, deliberately. Cancelling would stage a CANCELLED event for
     * a conversation that never happened, and would leave a tombstone in the event's "completed or
     * cancelled" list recording that somebody was considered and dropped — which is noise on the
     * host's own page and, worse, a thing a vendor could eventually be shown. Only a shortlist may
     * be deleted; everything past it is a real exchange and is cancelled, not erased.
     */
    @Transactional
    public void removeShortlisted(UUID bookingId, UUID actingUserId) {
        Booking booking = requireBooking(bookingId);
        if (!booking.getConsumerUserId().equals(actingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the person who saved this listing may remove it");
        }
        if (booking.getStatus() != BookingStatus.SHORTLISTED) {
            throw new PlatformException("ILLEGAL_TRANSITION",
                    "Only a shortlisted listing can be removed; cancel it instead",
                    HttpStatus.CONFLICT);
        }

        bookingRepo.delete(booking);
        log.info("Shortlisted booking {} removed by {}", bookingId, actingUserId);
    }

    public List<BookingResponse> listVendor(UUID vendorId) {
        if (vendorId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No vendor org context");
        }
        // Shortlisted rows are excluded at the query, not filtered after: this is the vendor's
        // own view and a consumer considering them is not theirs to see.
        return bookingRepo
                .findByVendorIdAndStatusNotOrderByCreatedAtDesc(vendorId, BookingStatus.SHORTLISTED)
                .stream().map(BookingResponse::from).toList();
    }

    @Transactional
    public BookingResponse quote(UUID bookingId, QuoteBookingRequest req, UUID actingUserId, UUID actingTenantId) {
        Booking booking = requireBooking(bookingId);
        requireVendorSide(booking, actingTenantId);
        requireStatus(booking, BookingStatus.INQUIRY);

        ListingServiceClient.ListingInfo listing = listingClient.getSnapshot(booking.getListingRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", booking.getListingRecordId().toString()));
        BigDecimal commissionRate = schemaRegistryClient.getCommissionRate(
                listing.verificationTier() != null ? listing.verificationTier() : "NONE",
                listing.objectType());

        String previousStatus = booking.getStatus().name();
        booking.setQuotedPrice(req.price());
        booking.setCurrency(req.currency() != null && !req.currency().isBlank() ? req.currency() : "INR");
        booking.setCommissionRate(commissionRate);
        booking.setCommissionAmount(req.price().multiply(commissionRate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        booking.setQuoteNote(req.quoteNote());
        booking.setStatus(BookingStatus.QUOTED);

        Booking saved = bookingRepo.save(booking);
        eventPublisher.publish(saved, "QUOTED", previousStatus, actingUserId);
        log.info("Booking {} quoted: price={} commission={}", saved.getId(),
                saved.getQuotedPrice(), saved.getCommissionAmount());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse confirm(UUID bookingId, UUID actingUserId, UUID actingTenantId) {
        Booking booking = requireBooking(bookingId);
        requireConsumer(booking, actingUserId);
        requireStatus(booking, BookingStatus.QUOTED);

        boolean claimed = listingClient.bookSlot(booking.getListingRecordId(), booking.getEventDate(), booking.getId());
        if (!claimed) {
            throw new PlatformException("SLOT_UNAVAILABLE",
                    "This date is no longer available for the listing", HttpStatus.CONFLICT);
        }

        String previousStatus = booking.getStatus().name();
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setAvailabilityClaimed(true);

        Booking saved = bookingRepo.save(booking);
        eventPublisher.publish(saved, "CONFIRMED", previousStatus, actingUserId);
        log.info("Booking {} confirmed, availability claimed", saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse complete(UUID bookingId, UUID actingUserId, UUID actingTenantId) {
        Booking booking = requireBooking(bookingId);
        requireEitherSide(booking, actingUserId, actingTenantId);
        requireStatus(booking, BookingStatus.CONFIRMED);
        if (booking.getEventDate().isAfter(LocalDate.now())) {
            throw new PlatformException("EVENT_NOT_YET_OCCURRED",
                    "Cannot complete a booking before its event date", HttpStatus.CONFLICT);
        }

        String previousStatus = booking.getStatus().name();
        booking.setStatus(BookingStatus.COMPLETED);

        // Completion is what makes the commission collectable. Booked at DUE rather than invoiced
        // straight away because billing is a deliberate admin act — an invoice number that nobody
        // issued is not an invoice. A zero-commission booking skips the queue entirely: there is
        // nothing to collect, and leaving it DUE would clog the queue with unactionable lines.
        boolean owed = booking.getCommissionAmount() != null
                && booking.getCommissionAmount().compareTo(BigDecimal.ZERO) > 0;
        booking.setSettlementStatus(owed ? SettlementStatus.DUE : SettlementStatus.PAID);
        if (!owed) booking.setSettledAt(Instant.now());

        Booking saved = bookingRepo.save(booking);
        eventPublisher.publish(saved, "COMPLETED", previousStatus, actingUserId);
        log.info("Booking {} completed", saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse cancel(UUID bookingId, CancelBookingRequest req, UUID actingUserId, UUID actingTenantId) {
        Booking booking = requireBooking(bookingId);
        requireEitherSide(booking, actingUserId, actingTenantId);
        // SHORTLISTED is deliberately absent: it is removed rather than cancelled, so that no
        // CANCELLED event is staged for an exchange the vendor never knew about. See
        // removeShortlisted.
        if (booking.getStatus() != BookingStatus.INQUIRY
                && booking.getStatus() != BookingStatus.QUOTED
                && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new PlatformException("ILLEGAL_TRANSITION",
                    "Cannot cancel a booking in status " + booking.getStatus(), HttpStatus.CONFLICT);
        }

        String previousStatus = booking.getStatus().name();
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            // Fail-loud client (see ListingServiceClient): a network/5xx failure here propagates
            // and the cancellation does not proceed, rather than silently leaving the vendor's
            // slot wrongly marked BOOKED forever. A legitimate `released=false` (already not
            // BOOKED under this bookingRef) is logged but does not block — booking-service's own
            // row is still the source of truth for whether this booking is cancelled.
            boolean released = listingClient.releaseSlot(
                    booking.getListingRecordId(), booking.getEventDate(), booking.getId());
            if (!released) {
                log.warn("Booking {} cancel: listing-service reported no matching BOOKED slot to release",
                        booking.getId());
            }
        }

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledByUserId(actingUserId);
        booking.setCancelReason(req.reason());
        // A cancelled booking owes nothing. Only reset while the receivable is still open — a
        // commission already invoiced or paid stays on the books, because it was.
        if (booking.getSettlementStatus() != null && booking.getSettlementStatus().isOpen()) {
            booking.setSettlementStatus(SettlementStatus.NOT_DUE);
        }

        Booking saved = bookingRepo.save(booking);
        eventPublisher.publish(saved, "CANCELLED", previousStatus, actingUserId);
        log.info("Booking {} cancelled by {}", saved.getId(), actingUserId);
        return BookingResponse.from(saved);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Booking requireBooking(UUID bookingId) {
        return bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId.toString()));
    }

    private void requireConsumer(Booking booking, UUID actingUserId) {
        if (actingUserId == null || !actingUserId.equals(booking.getConsumerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the consumer on this booking");
        }
    }

    private void requireVendorSide(Booking booking, UUID actingTenantId) {
        if (actingTenantId == null || !actingTenantId.equals(booking.getVendorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of the vendor org for this booking");
        }
    }

    private void requireEitherSide(Booking booking, UUID actingUserId, UUID actingTenantId) {
        boolean isConsumer = actingUserId != null && actingUserId.equals(booking.getConsumerUserId());
        boolean isVendor = actingTenantId != null && actingTenantId.equals(booking.getVendorId());
        if (!isConsumer && !isVendor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a party to this booking");
        }
    }

    private void requireStatus(Booking booking, BookingStatus expected) {
        if (booking.getStatus() != expected) {
            throw new PlatformException("ILLEGAL_TRANSITION",
                    "Booking is in status " + booking.getStatus() + ", expected " + expected,
                    HttpStatus.CONFLICT);
        }
    }
}
