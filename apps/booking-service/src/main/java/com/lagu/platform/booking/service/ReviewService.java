package com.lagu.platform.booking.service;

import com.lagu.platform.booking.client.ListingServiceClient;
import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.booking.domain.BookingRepository;
import com.lagu.platform.booking.domain.BookingStatus;
import com.lagu.platform.booking.domain.Review;
import com.lagu.platform.booking.domain.ReviewRepository;
import com.lagu.platform.booking.dto.CreateReviewRequest;
import com.lagu.platform.booking.dto.ListingRatingResponse;
import com.lagu.platform.booking.dto.ReviewResponse;
import com.lagu.platform.booking.dto.VendorReplyRequest;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What consumers have said about a vendor's listings.
 *
 * <p>Reviews live beside bookings because the fact that makes a marketplace rating worth trusting
 * — that the reviewer actually bought something — is this service's own knowledge. A review
 * elsewhere would have to ask across a boundary for the one thing that matters about it.
 *
 * <p>Anyone signed in may write one; whether it is <em>verified</em> is decided here, by looking
 * for a COMPLETED booking rather than by believing a claim in the request. Both kinds are shown,
 * and only verified ones move the average — an opinion the platform cannot stand behind should not
 * shift the figure a shopper is trusting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final ReviewRepository reviewRepo;
    private final BookingRepository bookingRepo;
    private final ListingServiceClient listingClient;

    @Transactional
    public ReviewResponse create(CreateReviewRequest req, UUID authorUserId) {
        // Fails closed, like every other read of a listing here: no listing, no review. It is also
        // where vendorId comes from, which is what makes the vendor-side read one query.
        ListingServiceClient.ListingInfo listing = listingClient.getSnapshot(req.listingRecordId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Listing", req.listingRecordId().toString()));

        // One per person per listing. The unique index enforces it regardless; catching it here is
        // what turns a constraint violation into a sentence someone can act on.
        reviewRepo.findByListingRecordIdAndAuthorUserId(req.listingRecordId(), authorUserId)
                .ifPresent(existing -> {
                    throw new PlatformException("ALREADY_REVIEWED",
                            "You have already reviewed this listing", HttpStatus.CONFLICT);
                });

        // The verification decision, made by looking rather than by being told.
        Optional<Booking> completed = bookingRepo
                .findFirstByConsumerUserIdAndListingRecordIdAndStatusOrderByUpdatedAtDesc(
                        authorUserId, req.listingRecordId(), BookingStatus.COMPLETED);

        Review review = new Review();
        review.setListingRecordId(req.listingRecordId());
        review.setVendorId(listing.tenantId());
        review.setAuthorUserId(authorUserId);
        review.setRating(req.rating());
        review.setBody(req.body() != null && !req.body().isBlank() ? req.body().trim() : null);
        review.setBookingId(completed.map(Booking::getId).orElse(null));
        review.setVerified(completed.isPresent());

        Review saved = reviewRepo.save(review);

        // Only a verified review moves the average, so only a verified one is worth a push. An
        // unverified review changes nothing a shopper sees on a card and the round trip would be
        // spent recomputing the same two numbers.
        if (saved.isVerified()) {
            pushAggregate(req.listingRecordId());
        }

        log.info("Review {} on listing {} by {}: rating={} verified={}",
                saved.getId(), req.listingRecordId(), authorUserId, saved.getRating(),
                saved.isVerified());
        return ReviewResponse.from(saved);
    }

    /** A listing's reviews as a shopper reads them: newest first, verified and unverified alike. */
    public List<ReviewResponse> listForListing(UUID listingRecordId) {
        return reviewRepo.findByListingRecordIdOrderByCreatedAtDesc(listingRecordId)
                .stream().map(ReviewResponse::from).toList();
    }

    /** Everything said about one vendor, so replying is one screen rather than a hunt. */
    public List<ReviewResponse> listForVendor(UUID vendorId) {
        if (vendorId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No vendor org context");
        }
        return reviewRepo.findByVendorIdOrderByCreatedAtDesc(vendorId)
                .stream().map(ReviewResponse::from).toList();
    }

    /**
     * The number behind a card's "4.8 (62)".
     *
     * <p>A listing with nothing verified said about it comes back with a null average rather than
     * a zero: "unrated" and "rated badly" are different claims and must render differently.
     */
    public ListingRatingResponse rating(UUID listingRecordId) {
        return reviewRepo.aggregateVerified(listingRecordId)
                .filter(a -> a.count() > 0)
                .map(a -> new ListingRatingResponse(listingRecordId, round1(a.average()), a.count()))
                .orElseGet(() -> new ListingRatingResponse(listingRecordId, null, 0));
    }

    /**
     * The vendor's right of reply.
     *
     * <p>Authorised on the vendor org the review is *about*, not on who is asking — a vendor may
     * answer criticism of themselves and nobody else. One reply per review, editable: a vendor who
     * replies in anger and thinks better of it should be able to fix it, and a thread would put
     * the platform in the middle of the argument a reply exists to avoid.
     */
    @Transactional
    public ReviewResponse reply(UUID reviewId, VendorReplyRequest req, UUID actingUserId,
                                UUID actingTenantId) {
        Review review = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId.toString()));

        if (actingTenantId == null || !actingTenantId.equals(review.getVendorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the vendor this review is about may reply to it");
        }

        review.setVendorReply(req.reply().trim());
        review.setVendorRepliedAt(Instant.now());
        review.setVendorRepliedBy(actingUserId);

        Review saved = reviewRepo.save(review);
        log.info("Vendor {} replied to review {}", actingTenantId, reviewId);
        return ReviewResponse.from(saved);
    }

    /**
     * Hands the recomputed aggregate to listing-service, which is what puts it on a marketplace
     * card. Best-effort inside the client — see {@link ListingServiceClient#applyRating} — because
     * the review itself is already saved and a derived read model is not worth failing a real
     * write for.
     */
    private void pushAggregate(UUID listingRecordId) {
        ListingRatingResponse agg = rating(listingRecordId);
        listingClient.applyRating(listingRecordId,
                agg.average() != null ? java.math.BigDecimal.valueOf(agg.average()) : null,
                agg.count());
    }

    /** One decimal place, which is all a star rating means. 4.8, not 4.7999999999999998. */
    private static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }
}
