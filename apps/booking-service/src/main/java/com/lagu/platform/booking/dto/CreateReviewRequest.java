package com.lagu.platform.booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Leaving a review.
 *
 * <p>Deliberately carries no booking id. Whether this is a verified review is not something the
 * caller gets to assert — the service looks for a COMPLETED booking of its own accord and links
 * it. That makes verified the default rather than something a consumer opts into by knowing an
 * identifier they have never seen, and it removes a claim the client could get wrong or lie about.
 */
public record CreateReviewRequest(
        @NotNull UUID listingRecordId,
        @NotNull @Min(value = 1, message = "rating must be between 1 and 5")
        @Max(value = 5, message = "rating must be between 1 and 5") Short rating,
        @Size(max = 4000, message = "review is too long") String body) {}
