package com.lagu.platform.booking.service;

import com.lagu.platform.booking.client.EventServiceClient;
import com.lagu.platform.booking.client.ListingServiceClient;
import com.lagu.platform.booking.client.ListingServiceClient.ListingInfo;
import com.lagu.platform.booking.client.SchemaRegistryClient;
import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.booking.domain.BookingRepository;
import com.lagu.platform.booking.domain.BookingStatus;
import com.lagu.platform.booking.dto.BookingResponse;
import com.lagu.platform.booking.dto.CancelBookingRequest;
import com.lagu.platform.booking.dto.CreateBookingRequest;
import com.lagu.platform.booking.event.BookingEventPublisher;
import com.lagu.platform.common.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A shortlist is a booking that has not been sent.
 *
 * <p>Choosing a venue is a compare-three-then-decide task and there was nowhere to keep the three,
 * so shoppers did it in browser tabs, off the platform. Making it a booking status costs no new
 * store and puts the candidates on the event's own Vendors tab beside the real inquiries.
 *
 * <p>The whole of the risk is on one side: <b>the vendor must never learn of it.</b> These tests
 * hold that invariant at each of the three places it could leak — the outbox on create, the
 * vendor's own listing, and what removing one leaves behind.
 */
class ShortlistTest {

    private BookingRepository bookingRepo;
    private BookingEventPublisher eventPublisher;
    private ListingServiceClient listingClient;
    private BookingService service;

    private final UUID consumerUserId = UUID.randomUUID();
    private final UUID vendorId = UUID.randomUUID();
    private final UUID listingRecordId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bookingRepo = mock(BookingRepository.class);
        eventPublisher = mock(BookingEventPublisher.class);
        listingClient = mock(ListingServiceClient.class);
        SchemaRegistryClient schemaRegistryClient = mock(SchemaRegistryClient.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);

        service = new BookingService(bookingRepo, listingClient, eventClient,
                schemaRegistryClient, eventPublisher);

        when(listingClient.getSnapshot(listingRecordId))
                .thenReturn(Optional.of(new ListingInfo(listingRecordId, vendorId, "VENUE", "PUBLISHED", "BASIC")));
        when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Booking shortlisted() {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setConsumerUserId(consumerUserId);
        b.setVendorId(vendorId);
        b.setListingRecordId(listingRecordId);
        b.setEventDate(LocalDate.now().plusDays(30));
        b.setStatus(BookingStatus.SHORTLISTED);
        return b;
    }

    private CreateBookingRequest request(Boolean shortlist) {
        return new CreateBookingRequest(
                listingRecordId, LocalDate.now().plusDays(30), null, null, null, shortlist);
    }

    // ── the vendor must not hear about it ────────────────────────────────────

    @Test
    void savingAListingStagesNothingForTheVendor() {
        BookingResponse resp = service.create(request(true), consumerUserId);

        assertThat(resp.status()).isEqualTo("SHORTLISTED");
        // The outbox is what automation-service turns into a notification. Staging anything here
        // tells a vendor they were asked about a date by someone who only bookmarked them.
        verify(eventPublisher, never()).publish(any(), anyString(), any(), any());
    }

    @Test
    void anOrdinaryInquiryStillNotifiesAsBefore() {
        service.create(request(null), consumerUserId);

        verify(eventPublisher).publish(any(), eq("INQUIRED"), eq(null), eq(consumerUserId));
    }

    @Test
    void theVendorsOwnListExcludesShortlistedRowsAtTheQuery() {
        when(bookingRepo.findByVendorIdAndStatusNotOrderByCreatedAtDesc(vendorId, BookingStatus.SHORTLISTED))
                .thenReturn(List.of());

        assertThat(service.listVendor(vendorId)).isEmpty();
        // Excluded by the query rather than filtered afterwards — a later caller reaching for the
        // unfiltered finder is the way this leaks back.
        verify(bookingRepo).findByVendorIdAndStatusNotOrderByCreatedAtDesc(vendorId, BookingStatus.SHORTLISTED);
    }

    // ── sending it ───────────────────────────────────────────────────────────

    @Test
    void sendingAShortlistIsTheMomentTheVendorLearnsOfIt() {
        Booking b = shortlisted();
        when(bookingRepo.findById(b.getId())).thenReturn(Optional.of(b));

        BookingResponse resp = service.inquire(b.getId(), consumerUserId);

        assertThat(resp.status()).isEqualTo("INQUIRY");
        verify(eventPublisher).publish(any(), eq("INQUIRED"), eq("SHORTLISTED"), eq(consumerUserId));
    }

    @Test
    void onlyTheConsumerWhoSavedItMaySendIt() {
        Booking b = shortlisted();
        when(bookingRepo.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.inquire(b.getId(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── removing it ──────────────────────────────────────────────────────────

    @Test
    void removingAShortlistDeletesTheRowRatherThanCancellingIt() {
        Booking b = shortlisted();
        when(bookingRepo.findById(b.getId())).thenReturn(Optional.of(b));

        service.removeShortlisted(b.getId(), consumerUserId);

        // No CANCELLED tombstone for a conversation that never happened, and nothing staged.
        verify(bookingRepo).delete(b);
        verify(eventPublisher, never()).publish(any(), anyString(), any(), any());
    }

    @Test
    void aRealInquiryCannotBeErasedThisWay() {
        Booking b = shortlisted();
        b.setStatus(BookingStatus.QUOTED);
        when(bookingRepo.findById(b.getId())).thenReturn(Optional.of(b));

        // Everything past a shortlist is a real exchange: it is cancelled, which the vendor is
        // told about, not deleted out from under them.
        assertThatThrownBy(() -> service.removeShortlisted(b.getId(), consumerUserId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cancel it instead");
        verify(bookingRepo, never()).delete(any());
    }

    @Test
    void aShortlistCannotBeCancelled() {
        Booking b = shortlisted();
        when(bookingRepo.findById(b.getId())).thenReturn(Optional.of(b));

        // The other half of the same rule: cancelling stages a CANCELLED event, so a shortlist
        // must not take that path at all.
        assertThatThrownBy(() ->
                service.cancel(b.getId(), new CancelBookingRequest(null), consumerUserId, null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Cannot cancel a booking in status SHORTLISTED");
    }
}
