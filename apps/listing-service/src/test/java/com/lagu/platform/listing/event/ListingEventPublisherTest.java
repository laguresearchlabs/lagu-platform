package com.lagu.platform.listing.event;

import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.events.ListingEvent;
import com.lagu.platform.listing.domain.ListingAvailability;
import com.lagu.platform.listing.domain.ListingAvailabilityRepository;
import com.lagu.platform.listing.domain.ListingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The days a listing cannot take, as they reach the marketplace.
 *
 * <p>search-service indexes a whole document rather than patching one, so this list is written on
 * <em>every</em> publish — a republish that omitted it would quietly mark every booked date free
 * again. That is why the publisher computes it itself instead of taking it as an argument, and it
 * is the property most worth pinning here.
 */
class ListingEventPublisherTest {

    private TransactionalOutbox outbox;
    private ListingAvailabilityRepository availabilityRepo;
    private ListingEventPublisher publisher;

    private final UUID recordId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        outbox = mock(TransactionalOutbox.class);
        availabilityRepo = mock(ListingAvailabilityRepository.class);
        publisher = new ListingEventPublisher(outbox, availabilityRepo);
    }

    private ListingSnapshot snapshot() {
        ListingSnapshot snap = new ListingSnapshot();
        snap.setRecordId(recordId);
        snap.setTenantId(UUID.randomUUID());
        snap.setObjectType("VENUE");
        snap.setData(Map.of("name", "Lotus Banquets"));
        return snap;
    }

    private ListingAvailability slot(String date, String type) {
        ListingAvailability a = new ListingAvailability();
        a.setRecordId(recordId);
        a.setSlotDate(LocalDate.parse(date));
        a.setSlotType(type);
        return a;
    }

    private ListingEvent captureEvent() {
        ArgumentCaptor<ListingEvent> captor = ArgumentCaptor.forClass(ListingEvent.class);
        verify(outbox).stage(anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void bookedAndBlockedDaysBothCountAsUnavailable() {
        // A vendor blocking a day and a consumer booking one are the same fact to a shopper: the
        // listing cannot take that date.
        when(availabilityRepo.findByRecordIdAndSlotDateBetween(eq(recordId), any(), any()))
                .thenReturn(List.of(
                        slot("2026-11-14", "BOOKED"),
                        slot("2026-12-25", "BLOCKED")));

        publisher.publishPublished(snapshot());

        assertThat(captureEvent().getUnavailableDates())
                .containsExactly("2026-11-14", "2026-12-25");
    }

    @Test
    void anAvailableRowIsNotAnUnavailableDay() {
        // Releasing a booking leaves the row behind as AVAILABLE. Treating its presence as
        // unavailability would keep a cancelled date off the marketplace forever.
        when(availabilityRepo.findByRecordIdAndSlotDateBetween(eq(recordId), any(), any()))
                .thenReturn(List.of(
                        slot("2026-11-14", "AVAILABLE"),
                        slot("2026-11-15", "BOOKED")));

        publisher.publishPublished(snapshot());

        assertThat(captureEvent().getUnavailableDates()).containsExactly("2026-11-15");
    }

    @Test
    void aListingNobodyHasBookedIsFreeOnEveryDate() {
        when(availabilityRepo.findByRecordIdAndSlotDateBetween(eq(recordId), any(), any()))
                .thenReturn(List.of());

        publisher.publishPublished(snapshot());

        // Empty, not null: search-service writes what it is given, and null would land as an
        // absent field rather than as "nothing taken".
        assertThat(captureEvent().getUnavailableDates()).isEmpty();
    }

    @Test
    void onlyTheSearchableHorizonIsAskedFor() {
        when(availabilityRepo.findByRecordIdAndSlotDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        publisher.publishPublished(snapshot());

        // From today rather than from the beginning of time: nobody searches for a venue for last
        // Tuesday, and carrying the history would grow this array without bound for a listing that
        // sells well.
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(availabilityRepo).findByRecordIdAndSlotDateBetween(eq(recordId),
                from.capture(), to.capture());

        assertThat(from.getValue()).isEqualTo(LocalDate.now());
        assertThat(to.getValue()).isEqualTo(LocalDate.now().plusMonths(18));
    }
}
