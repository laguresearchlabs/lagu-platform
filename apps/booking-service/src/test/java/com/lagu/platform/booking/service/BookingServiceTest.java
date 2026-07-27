package com.lagu.platform.booking.service;

import com.lagu.platform.booking.client.ListingServiceClient;
import com.lagu.platform.booking.client.ListingServiceClient.ListingInfo;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final ListingServiceClient listingClient = mock(ListingServiceClient.class);
    private final SchemaRegistryClient schemaRegistryClient = mock(SchemaRegistryClient.class);
    private final BookingEventPublisher eventPublisher = mock(BookingEventPublisher.class);

    private final BookingService service = new BookingService(
            bookingRepo, listingClient, schemaRegistryClient, eventPublisher);

    private final UUID consumerUserId = UUID.randomUUID();
    private final UUID vendorId = UUID.randomUUID();
    private final UUID listingRecordId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void stubSave() {
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Booking booking(BookingStatus status) {
        Booking b = new Booking();
        b.setId(bookingId);
        b.setConsumerUserId(consumerUserId);
        b.setVendorId(vendorId);
        b.setListingRecordId(listingRecordId);
        b.setEventDate(LocalDate.now().plusDays(30));
        b.setStatus(status);
        return b;
    }

    private ListingInfo publishedListing() {
        return new ListingInfo(listingRecordId, vendorId, "VENUE", "PUBLISHED", "BASIC");
    }

    // ---- create ----

    @Test
    void createSucceedsForPublishedListing() {
        when(listingClient.getSnapshot(listingRecordId)).thenReturn(Optional.of(publishedListing()));
        CreateBookingRequest req = new CreateBookingRequest(listingRecordId, LocalDate.now().plusDays(10), null, "please");

        BookingResponse resp = service.create(req, consumerUserId);

        assertThat(resp.status()).isEqualTo("INQUIRY");
        assertThat(resp.vendorId()).isEqualTo(vendorId);
        assertThat(resp.consumerUserId()).isEqualTo(consumerUserId);
        verify(eventPublisher).publish(any(), eq("INQUIRED"), isNull(), eq(consumerUserId));
    }

    @Test
    void createFailsWhenListingNotFound() {
        when(listingClient.getSnapshot(listingRecordId)).thenReturn(Optional.empty());
        CreateBookingRequest req = new CreateBookingRequest(listingRecordId, LocalDate.now().plusDays(10), null, null);

        assertThatThrownBy(() -> service.create(req, consumerUserId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createFailsWhenListingNotPublished() {
        ListingInfo unpublished = new ListingInfo(listingRecordId, vendorId, "VENUE", "UNPUBLISHED", "BASIC");
        when(listingClient.getSnapshot(listingRecordId)).thenReturn(Optional.of(unpublished));
        CreateBookingRequest req = new CreateBookingRequest(listingRecordId, LocalDate.now().plusDays(10), null, null);

        assertThatThrownBy(() -> service.create(req, consumerUserId))
                .isInstanceOf(PlatformException.class);
    }

    // ---- get / listMine / listVendor authorization ----

    @Test
    void getSucceedsForConsumer() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        BookingResponse resp = service.get(bookingId, consumerUserId, null);

        assertThat(resp.id()).isEqualTo(bookingId);
    }

    @Test
    void getSucceedsForVendorOrgMember() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        BookingResponse resp = service.get(bookingId, null, vendorId);

        assertThat(resp.id()).isEqualTo(bookingId);
    }

    @Test
    void getRejectsUnrelatedCaller() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        assertThatThrownBy(() -> service.get(bookingId, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getThrowsNotFoundForMissingBooking() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(bookingId, consumerUserId, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listVendorRejectsCallerWithNoOrgContext() {
        assertThatThrownBy(() -> service.listVendor(null))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ---- quote ----

    @Test
    void quoteSucceedsForVendorSideFromInquiry() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));
        when(listingClient.getSnapshot(listingRecordId)).thenReturn(Optional.of(publishedListing()));
        when(schemaRegistryClient.getCommissionRate("BASIC", "VENUE")).thenReturn(new BigDecimal("15.00"));

        QuoteBookingRequest req = new QuoteBookingRequest(new BigDecimal("1000.00"), "INR", "note");
        BookingResponse resp = service.quote(bookingId, req, UUID.randomUUID(), vendorId);

        assertThat(resp.status()).isEqualTo("QUOTED");
        assertThat(resp.quotedPrice()).isEqualByComparingTo("1000.00");
        assertThat(resp.commissionRate()).isEqualByComparingTo("15.00");
        assertThat(resp.commissionAmount()).isEqualByComparingTo("150.00"); // 1000 * 15% (rate is a percentage)
        verify(eventPublisher).publish(any(), eq("QUOTED"), eq("INQUIRY"), any());
    }

    @Test
    void quoteRejectsNonVendorCaller() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        QuoteBookingRequest req = new QuoteBookingRequest(new BigDecimal("1000.00"), null, null);
        assertThatThrownBy(() -> service.quote(bookingId, req, consumerUserId, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(schemaRegistryClient);
    }

    @Test
    void quoteRejectsWrongOrg() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        QuoteBookingRequest req = new QuoteBookingRequest(new BigDecimal("1000.00"), null, null);
        assertThatThrownBy(() -> service.quote(bookingId, req, null, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void quoteFailsWhenNotInInquiryStatus() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.QUOTED)));

        QuoteBookingRequest req = new QuoteBookingRequest(new BigDecimal("1000.00"), null, null);
        assertThatThrownBy(() -> service.quote(bookingId, req, null, vendorId))
                .isInstanceOf(PlatformException.class);
    }

    // ---- confirm ----

    @Test
    void confirmSucceedsWhenSlotClaimed() {
        Booking b = booking(BookingStatus.QUOTED);
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(b));
        when(listingClient.bookSlot(listingRecordId, b.getEventDate(), bookingId)).thenReturn(true);

        BookingResponse resp = service.confirm(bookingId, consumerUserId, null);

        assertThat(resp.status()).isEqualTo("CONFIRMED");
        assertThat(resp.availabilityClaimed()).isTrue();
        verify(eventPublisher).publish(any(), eq("CONFIRMED"), eq("QUOTED"), eq(consumerUserId));
    }

    @Test
    void confirmFailsWhenSlotLostRace() {
        // The core race-safety property: confirm must not proceed if bookSlot lost the race.
        Booking b = booking(BookingStatus.QUOTED);
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(b));
        when(listingClient.bookSlot(listingRecordId, b.getEventDate(), bookingId)).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(bookingId, consumerUserId, null))
                .isInstanceOf(PlatformException.class);

        assertThat(b.getStatus()).isEqualTo(BookingStatus.QUOTED); // unchanged
        verify(bookingRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void confirmRejectsNonConsumerCaller() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.QUOTED)));

        assertThatThrownBy(() -> service.confirm(bookingId, UUID.randomUUID(), null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(listingClient);
    }

    @Test
    void confirmFailsWhenNotQuoted() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        assertThatThrownBy(() -> service.confirm(bookingId, consumerUserId, null))
                .isInstanceOf(PlatformException.class);
    }

    // ---- complete ----

    @Test
    void completeSucceedsAfterEventDatePassed() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setEventDate(LocalDate.now().minusDays(1));
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(b));

        BookingResponse resp = service.complete(bookingId, consumerUserId, null);

        assertThat(resp.status()).isEqualTo("COMPLETED");
    }

    @Test
    void completeFailsBeforeEventDate() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setEventDate(LocalDate.now().plusDays(5));
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.complete(bookingId, consumerUserId, null))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void completeAllowsVendorSideToo() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setEventDate(LocalDate.now().minusDays(1));
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(b));

        BookingResponse resp = service.complete(bookingId, null, vendorId);

        assertThat(resp.status()).isEqualTo("COMPLETED");
    }

    // ---- cancel ----

    @Test
    void cancelFromInquiryDoesNotCallListingService() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        BookingResponse resp = service.cancel(bookingId, new CancelBookingRequest("changed my mind"),
                consumerUserId, null);

        assertThat(resp.status()).isEqualTo("CANCELLED");
        assertThat(resp.cancelledByUserId()).isEqualTo(consumerUserId);
        verifyNoInteractions(listingClient);
    }

    @Test
    void cancelFromConfirmedReleasesSlot() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setAvailabilityClaimed(true);
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(b));
        when(listingClient.releaseSlot(listingRecordId, b.getEventDate(), bookingId)).thenReturn(true);

        BookingResponse resp = service.cancel(bookingId, new CancelBookingRequest("no longer needed"),
                consumerUserId, null);

        assertThat(resp.status()).isEqualTo("CANCELLED");
        verify(listingClient).releaseSlot(listingRecordId, b.getEventDate(), bookingId);
    }

    @Test
    void cancelFromConfirmedProceedsEvenWhenReleaseReturnsFalse() {
        // A legitimate "nothing to release" result must not block the local cancellation —
        // booking-service's own row is still authoritative for whether this booking is cancelled.
        Booking b = booking(BookingStatus.CONFIRMED);
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(b));
        when(listingClient.releaseSlot(any(), any(), any())).thenReturn(false);

        BookingResponse resp = service.cancel(bookingId, new CancelBookingRequest(null), consumerUserId, null);

        assertThat(resp.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelFailsForTerminalStatus() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.COMPLETED)));

        assertThatThrownBy(() -> service.cancel(bookingId, new CancelBookingRequest(null), consumerUserId, null))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void cancelAllowsVendorSideToo() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        BookingResponse resp = service.cancel(bookingId, new CancelBookingRequest("declining"), null, vendorId);

        assertThat(resp.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelRejectsUnrelatedCaller() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(booking(BookingStatus.INQUIRY)));

        assertThatThrownBy(() -> service.cancel(bookingId, new CancelBookingRequest(null),
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
