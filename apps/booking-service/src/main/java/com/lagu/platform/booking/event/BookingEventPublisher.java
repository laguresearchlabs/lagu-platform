package com.lagu.platform.booking.event;

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
 * <p>automation-service consumes these (consumer-side notifications on quoted/confirmed/
 * cancelled/completed — see AutomationSeeder's booking triggers); vendor-side notifications are
 * not wired yet, see that class's Javadoc for why.
 */
@Component
@RequiredArgsConstructor
public class BookingEventPublisher {

    private final TransactionalOutbox outbox;

    public void publish(Booking booking, String eventType, String previousStatus, UUID changedBy) {
        BookingEvent event = BookingEvent.builder()
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
                .occurredAt(Instant.now())
                .build();
        outbox.stage(PlatformTopics.BOOKING_EVENTS, bookingKey(booking), event);
    }

    private String bookingKey(Booking booking) {
        return booking.getVendorId() + ":" + booking.getId();
    }
}
