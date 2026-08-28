package com.lagu.platform.booking.event;

import com.lagu.platform.booking.client.VendorServiceClient;
import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.events.BookingEvent;
import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Stages BookingEvents in the transactional outbox ({@code booking_outbox}) rather than sending
 * to Kafka directly — same pattern as record-service's RecordEventPublisher. Every publish method
 * must be called inside the same @Transactional service method that mutates the booking row, so
 * the event and the change commit or roll back together.
 *
 * <p>automation-service consumes these and notifies both parties: the consumer on quoted/
 * confirmed/cancelled/completed, and the vendor on inquired/confirmed/cancelled/completed. Two
 * fields exist purely so those vendor-side rules can be expressed — see
 * {@link #resolveActorSide} and {@link BookingEvent#getVendorRecipientUserId()}.
 */
@Component
@RequiredArgsConstructor
public class BookingEventPublisher {

    private final TransactionalOutbox outbox;
    private final VendorServiceClient vendorClient;

    public void publish(Booking booking, String eventType, String previousStatus, UUID changedBy) {
        BookingEvent.BookingEventBuilder builder = BookingEvent.builder()
                .eventType(eventType)
                .bookingId(booking.getId())
                .consumerUserId(booking.getConsumerUserId())
                .vendorTenantId(booking.getVendorId())
                .listingRecordId(booking.getListingRecordId())
                .linkedEventId(booking.getEventId())
                .eventDate(booking.getEventDate())
                .previousStatus(previousStatus)
                .currentStatus(booking.getStatus().name())
                .quotedPrice(booking.getQuotedPrice())
                .commissionAmount(booking.getCommissionAmount())
                .changedBy(changedBy)
                .actorSide(resolveActorSide(booking, changedBy))
                .occurredAt(Instant.now());

        // Resolved per publish rather than cached: an org's owner and contact address can both
        // change, and a stale value sends someone else's business to a former member. The call is
        // best-effort (see VendorServiceClient) so a vendor-service outage costs the notification,
        // never the booking.
        vendorClient.findNotificationTarget(booking.getVendorId()).ifPresent(target -> builder
                .vendorRecipientUserId(target.userId())
                .vendorRecipientEmail(target.contactEmail()));

        outbox.stage(PlatformTopics.BOOKING_EVENTS, bookingKey(booking), builder.build());
    }

    /**
     * Which party acted. Anyone who is not the consumer on this booking reached these endpoints
     * through booking-service's {@code requireVendorSide}/{@code requireEitherSide} guards, which
     * admit only the consumer or a member of the owning vendor org — so "not the consumer" is
     * exactly "the vendor side", and no extra lookup is needed to tell them apart.
     *
     * <p>A null actor (a system-initiated transition) is treated as the vendor side, which is the
     * conservative choice: it suppresses a vendor notification rather than sending one that reads
     * as if the customer had acted.
     */
    private String resolveActorSide(Booking booking, UUID changedBy) {
        return changedBy != null && changedBy.equals(booking.getConsumerUserId()) ? "CONSUMER" : "VENDOR";
    }

    private String bookingKey(Booking booking) {
        return booking.getVendorId() + ":" + booking.getId();
    }
}
