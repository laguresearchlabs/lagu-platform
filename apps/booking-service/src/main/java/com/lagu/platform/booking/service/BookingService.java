package com.lagu.platform.booking.service;

import com.lagu.platform.booking.client.ListingServiceClient;
import com.lagu.platform.booking.client.SchemaRegistryClient;
import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.booking.domain.BookingRepository;
import com.lagu.platform.booking.domain.BookingStatus;
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
        booking.setInquiryMessage(req.inquiryMessage());
        booking.setStatus(BookingStatus.INQUIRY);

        Booking saved = bookingRepo.save(booking);
        eventPublisher.publish(saved, "INQUIRED", null, consumerUserId);
        log.info("Booking {} inquired: consumer={} listing={} date={}",
                saved.getId(), consumerUserId, req.listingRecordId(), req.eventDate());
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

    public List<BookingResponse> listVendor(UUID vendorId) {
        if (vendorId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No vendor org context");
        }
        return bookingRepo.findByVendorIdOrderByCreatedAtDesc(vendorId).stream()
                .map(BookingResponse::from).toList();
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

        Booking saved = bookingRepo.save(booking);
        eventPublisher.publish(saved, "COMPLETED", previousStatus, actingUserId);
        log.info("Booking {} completed", saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse cancel(UUID bookingId, CancelBookingRequest req, UUID actingUserId, UUID actingTenantId) {
        Booking booking = requireBooking(bookingId);
        requireEitherSide(booking, actingUserId, actingTenantId);
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
