package com.lagu.platform.automation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.events.BookingEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No test existed for this class before (parseRecordEvent/parseWorkflowEvent are untested too) —
 * this covers the new parseBookingEvent logic, since that's where the actual new mapping risk
 * (BookingEvent's top-level fields -> AutomationEventContext.data, for {{data.X}} templating) is.
 */
class AutomationEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AutomationEventParser parser = new AutomationEventParser(objectMapper);

    @Test
    void parseBookingEventMapsFieldsIntoDataMapForTemplating() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID consumerUserId = UUID.randomUUID();
        UUID vendorTenantId = UUID.randomUUID();
        UUID listingRecordId = UUID.randomUUID();
        UUID changedBy = UUID.randomUUID();

        BookingEvent event = BookingEvent.builder()
                .eventType("QUOTED")
                .bookingId(bookingId)
                .consumerUserId(consumerUserId)
                .vendorTenantId(vendorTenantId)
                .listingRecordId(listingRecordId)
                .eventDate(LocalDate.of(2026, 8, 15))
                .previousStatus("INQUIRY")
                .currentStatus("QUOTED")
                .quotedPrice(new BigDecimal("1500.00"))
                .commissionAmount(new BigDecimal("225.00"))
                .changedBy(changedBy)
                .occurredAt(Instant.now())
                .build();

        AutomationEventContext ctx = parser.parseBookingEvent(objectMapper.writeValueAsString(event));

        assertThat(ctx).isNotNull();
        assertThat(ctx.getEventType()).isEqualTo("QUOTED");
        assertThat(ctx.getTenantId()).isEqualTo(vendorTenantId);
        assertThat(ctx.getRecordId()).isEqualTo(bookingId);
        assertThat(ctx.getObjectType()).isNull(); // not schema-registry-driven
        assertThat(ctx.getPreviousStatus()).isEqualTo("INQUIRY");
        assertThat(ctx.getCurrentStatus()).isEqualTo("QUOTED");
        assertThat(ctx.getChangedBy()).isEqualTo(changedBy);

        assertThat(ctx.getData())
                .containsEntry("consumerUserId", consumerUserId.toString())
                .containsEntry("vendorTenantId", vendorTenantId.toString())
                .containsEntry("listingRecordId", listingRecordId.toString())
                .containsEntry("eventDate", "2026-08-15");
        assertThat((BigDecimal) ctx.getData().get("quotedPrice")).isEqualByComparingTo("1500.00");
        assertThat((BigDecimal) ctx.getData().get("commissionAmount")).isEqualByComparingTo("225.00");
    }

    @Test
    void parseBookingEventHandlesNullableFields() throws Exception {
        BookingEvent event = BookingEvent.builder()
                .eventType("INQUIRED")
                .bookingId(UUID.randomUUID())
                .consumerUserId(UUID.randomUUID())
                .vendorTenantId(UUID.randomUUID())
                .listingRecordId(UUID.randomUUID())
                .eventDate(LocalDate.now().plusDays(10))
                .currentStatus("INQUIRY")
                .occurredAt(Instant.now())
                // quotedPrice/commissionAmount/changedBy deliberately left null
                .build();

        AutomationEventContext ctx = parser.parseBookingEvent(objectMapper.writeValueAsString(event));

        assertThat(ctx).isNotNull();
        assertThat(ctx.getData().get("quotedPrice")).isNull();
        assertThat(ctx.getData().get("commissionAmount")).isNull();
        assertThat(ctx.getChangedBy()).isNull();
    }

    @Test
    void parseBookingEventReturnsNullForMalformedJson() {
        assertThat(parser.parseBookingEvent("not valid json")).isNull();
    }
}
