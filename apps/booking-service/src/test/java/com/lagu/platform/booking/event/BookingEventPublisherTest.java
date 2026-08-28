package com.lagu.platform.booking.event;

import com.lagu.platform.booking.client.VendorServiceClient;
import com.lagu.platform.booking.client.VendorServiceClient.NotificationTarget;
import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.booking.domain.BookingStatus;
import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.events.BookingEvent;
import com.lagu.platform.events.PlatformTopics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two fields that decide who hears about a booking. Both are computed here rather than
 * in automation-service, because its ConditionEvaluator can only compare a field to a constant —
 * so if these come out wrong, the wrong party gets notified and no rule downstream can correct it.
 */
class BookingEventPublisherTest {

    private final TransactionalOutbox outbox = mock(TransactionalOutbox.class);
    private final VendorServiceClient vendorClient = mock(VendorServiceClient.class);
    private final BookingEventPublisher publisher = new BookingEventPublisher(outbox, vendorClient);

    private final UUID consumerUserId = UUID.randomUUID();
    private final UUID vendorId = UUID.randomUUID();
    private final UUID vendorOwnerId = UUID.randomUUID();
    private final UUID vendorStaffId = UUID.randomUUID();

    private Booking booking() {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setConsumerUserId(consumerUserId);
        b.setVendorId(vendorId);
        b.setListingRecordId(UUID.randomUUID());
        b.setEventDate(LocalDate.now().plusDays(30));
        b.setStatus(BookingStatus.CONFIRMED);
        return b;
    }

    private BookingEvent publishAndCapture(Booking b, String eventType, UUID changedBy) {
        publisher.publish(b, eventType, "QUOTED", changedBy);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).stage(eq(PlatformTopics.BOOKING_EVENTS), eq(vendorId + ":" + b.getId()),
                payload.capture());
        return (BookingEvent) payload.getValue();
    }

    // ---- actorSide ----

    @Test
    void actorSideIsConsumerWhenTheConsumerActed() {
        when(vendorClient.findNotificationTarget(vendorId))
                .thenReturn(Optional.of(new NotificationTarget(vendorOwnerId, "bookings@venue.example")));

        assertThat(publishAndCapture(booking(), "CONFIRMED", consumerUserId).getActorSide())
                .isEqualTo("CONSUMER");
    }

    @Test
    void actorSideIsVendorForAnyOtherActor() {
        // booking-service's guards admit only the consumer or a member of the owning vendor org,
        // so "not the consumer" is exactly "the vendor side" — including staff who are not the
        // owner, which is the case a naive changedBy == ownerId check would get wrong.
        when(vendorClient.findNotificationTarget(vendorId))
                .thenReturn(Optional.of(new NotificationTarget(vendorOwnerId, "bookings@venue.example")));

        assertThat(publishAndCapture(booking(), "CANCELLED", vendorStaffId).getActorSide())
                .isEqualTo("VENDOR");
    }

    @Test
    void actorSideIsVendorWhenThereIsNoActor() {
        // A system-initiated transition suppresses the vendor notification rather than sending
        // one that reads as if the customer had acted.
        when(vendorClient.findNotificationTarget(vendorId))
                .thenReturn(Optional.of(new NotificationTarget(vendorOwnerId, "bookings@venue.example")));

        assertThat(publishAndCapture(booking(), "CANCELLED", null).getActorSide())
                .isEqualTo("VENDOR");
    }

    // ---- recipient resolution ----

    @Test
    void carriesTheResolvedVendorOwnerAsRecipient() {
        when(vendorClient.findNotificationTarget(vendorId))
                .thenReturn(Optional.of(new NotificationTarget(vendorOwnerId, "bookings@venue.example")));

        assertThat(publishAndCapture(booking(), "INQUIRED", consumerUserId).getVendorRecipientUserId())
                .isEqualTo(vendorOwnerId);
    }

    @Test
    void stillStagesTheEventWhenTheRecipientCannotBeResolved() {
        // The whole point of the best-effort client: a vendor-service outage must cost the
        // notification, not the booking. The event is staged either way, with a null recipient
        // that the seeded triggers' IS_NOT_NULL condition then filters out.
        when(vendorClient.findNotificationTarget(vendorId)).thenReturn(Optional.empty());

        BookingEvent event = publishAndCapture(booking(), "INQUIRED", consumerUserId);

        assertThat(event.getVendorRecipientUserId()).isNull();
        assertThat(event.getEventType()).isEqualTo("INQUIRED");
        assertThat(event.getConsumerUserId()).isEqualTo(consumerUserId);
    }

    @Test
    void carriesTheOrgsContactEmailForTheEmailHalfOfTheNotification() {
        when(vendorClient.findNotificationTarget(vendorId))
                .thenReturn(Optional.of(new NotificationTarget(vendorOwnerId, "bookings@venue.example")));

        assertThat(publishAndCapture(booking(), "INQUIRED", consumerUserId).getVendorRecipientEmail())
                .isEqualTo("bookings@venue.example");
    }

    @Test
    void stillNamesARecipientWhenTheOrgHasNoContactEmail() {
        // `email` is optional on the VENDOR schema. The in-app half must still be addressed and
        // delivered — only the email half is lost.
        when(vendorClient.findNotificationTarget(vendorId))
                .thenReturn(Optional.of(new NotificationTarget(vendorOwnerId, null)));

        BookingEvent event = publishAndCapture(booking(), "INQUIRED", consumerUserId);

        assertThat(event.getVendorRecipientUserId()).isEqualTo(vendorOwnerId);
        assertThat(event.getVendorRecipientEmail()).isNull();
    }

    @Test
    void keepsTheConsumerRecipientIndependentOfTheVendorLookup() {
        // Consumer-side notifications template off consumerUserId and must not regress when the
        // vendor lookup fails — they were working before this field existed.
        when(vendorClient.findNotificationTarget(vendorId)).thenReturn(Optional.empty());

        assertThat(publishAndCapture(booking(), "QUOTED", vendorStaffId).getConsumerUserId())
                .isEqualTo(consumerUserId);
    }
}
