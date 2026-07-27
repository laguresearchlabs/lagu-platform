package com.lagu.platform.booking.dto;

import com.lagu.platform.booking.domain.Booking;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Builder
public record BookingResponse(
        UUID id,
        UUID consumerUserId,
        UUID vendorId,
        UUID listingRecordId,
        UUID eventId,
        LocalDate eventDate,
        String status,
        String inquiryMessage,
        BigDecimal quotedPrice,
        String currency,
        BigDecimal commissionRate,
        BigDecimal commissionAmount,
        String quoteNote,
        UUID cancelledByUserId,
        String cancelReason,
        boolean availabilityClaimed,
        Instant createdAt,
        Instant updatedAt) {

    public static BookingResponse from(Booking b) {
        return BookingResponse.builder()
                .id(b.getId())
                .consumerUserId(b.getConsumerUserId())
                .vendorId(b.getVendorId())
                .listingRecordId(b.getListingRecordId())
                .eventId(b.getEventId())
                .eventDate(b.getEventDate())
                .status(b.getStatus().name())
                .inquiryMessage(b.getInquiryMessage())
                .quotedPrice(b.getQuotedPrice())
                .currency(b.getCurrency())
                .commissionRate(b.getCommissionRate())
                .commissionAmount(b.getCommissionAmount())
                .quoteNote(b.getQuoteNote())
                .cancelledByUserId(b.getCancelledByUserId())
                .cancelReason(b.getCancelReason())
                .availabilityClaimed(b.isAvailabilityClaimed())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }
}
