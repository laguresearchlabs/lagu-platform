package com.lagu.platform.booking.dto;

import java.util.UUID;

/**
 * The number behind a card's "4.8 (62)".
 *
 * @param average null for a listing nothing verified has been said about — "unrated", which is a
 *     different thing from a low score and must render differently. A genuine average of zero
 *     cannot occur, since the rating floor is 1.
 * @param count how many verified reviews the average was computed from. Unverified reviews are
 *     shown on the listing but excluded here: an opinion the platform cannot stand behind should
 *     not move the figure a shopper is trusting.
 */
public record ListingRatingResponse(UUID listingRecordId, Double average, long count) {}
