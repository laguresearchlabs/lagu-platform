package com.lagu.platform.booking.dto;

import com.lagu.platform.booking.domain.Review;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ReviewResponse(
        UUID id,
        UUID listingRecordId,
        UUID vendorId,
        UUID authorUserId,
        short rating,
        String body,
        /** Whether a COMPLETED booking backs this. Only verified reviews move the average. */
        boolean verified,
        String vendorReply,
        Instant vendorRepliedAt,
        Instant createdAt) {

    public static ReviewResponse from(Review r) {
        return ReviewResponse.builder()
                .id(r.getId())
                .listingRecordId(r.getListingRecordId())
                .vendorId(r.getVendorId())
                .authorUserId(r.getAuthorUserId())
                .rating(r.getRating())
                .body(r.getBody())
                .verified(r.isVerified())
                .vendorReply(r.getVendorReply())
                .vendorRepliedAt(r.getVendorRepliedAt())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
