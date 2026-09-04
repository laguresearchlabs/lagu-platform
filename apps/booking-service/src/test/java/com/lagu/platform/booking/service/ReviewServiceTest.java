package com.lagu.platform.booking.service;

import com.lagu.platform.booking.client.ListingServiceClient;
import com.lagu.platform.booking.client.ListingServiceClient.ListingInfo;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What makes a rating worth trusting.
 *
 * <p>Anyone signed in may write a review; whether it is <em>verified</em> is decided by looking for
 * a COMPLETED booking, never by believing the request. Both kinds are shown and only verified ones
 * move the average — an opinion the platform cannot stand behind must not shift the one number a
 * shopper is relying on.
 */
class ReviewServiceTest {

    private ReviewRepository reviewRepo;
    private BookingRepository bookingRepo;
    private ListingServiceClient listingClient;
    private ReviewService service;

    private final UUID authorUserId = UUID.randomUUID();
    private final UUID vendorId = UUID.randomUUID();
    private final UUID listingRecordId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reviewRepo = mock(ReviewRepository.class);
        bookingRepo = mock(BookingRepository.class);
        listingClient = mock(ListingServiceClient.class);
        service = new ReviewService(reviewRepo, bookingRepo, listingClient);

        when(listingClient.getSnapshot(listingRecordId)).thenReturn(Optional.of(
                new ListingInfo(listingRecordId, vendorId, "VENUE", "PUBLISHED", "BASIC")));
        when(reviewRepo.findByListingRecordIdAndAuthorUserId(any(), any()))
                .thenReturn(Optional.empty());
        when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        noCompletedBooking();
    }

    private void noCompletedBooking() {
        when(bookingRepo.findFirstByConsumerUserIdAndListingRecordIdAndStatusOrderByUpdatedAtDesc(
                any(), any(), eq(BookingStatus.COMPLETED))).thenReturn(Optional.empty());
    }

    private Booking completedBooking() {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setConsumerUserId(authorUserId);
        b.setListingRecordId(listingRecordId);
        b.setStatus(BookingStatus.COMPLETED);
        return b;
    }

    private CreateReviewRequest request(short rating) {
        return new CreateReviewRequest(listingRecordId, rating, "Lovely hall, slow service.");
    }

    // ── verification is decided, not claimed ─────────────────────────────────

    @Test
    void aReviewBackedByACompletedBookingIsVerified() {
        Booking booking = completedBooking();
        when(bookingRepo.findFirstByConsumerUserIdAndListingRecordIdAndStatusOrderByUpdatedAtDesc(
                authorUserId, listingRecordId, BookingStatus.COMPLETED))
                .thenReturn(Optional.of(booking));

        ReviewResponse resp = service.create(request((short) 5), authorUserId);

        assertThat(resp.verified()).isTrue();
    }

    @Test
    void aReviewFromSomebodyWhoNeverBookedIsKeptButMarkedUnverified() {
        // Allowed on purpose — it is shown, it just cannot move the average.
        ReviewResponse resp = service.create(request((short) 1), authorUserId);

        assertThat(resp.verified()).isFalse();
    }

    @Test
    void theRequestCannotAssertItsOwnVerification() {
        // There is no booking id on CreateReviewRequest at all, which is the point: verification
        // is not a claim a caller gets to make about itself. This pins the shape.
        assertThat(CreateReviewRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("listingRecordId", "rating", "body");
    }

    // ── the cheapest attack on a rating ──────────────────────────────────────

    @Test
    void onePersonCannotReviewOneListingTwice() {
        when(reviewRepo.findByListingRecordIdAndAuthorUserId(listingRecordId, authorUserId))
                .thenReturn(Optional.of(new Review()));

        assertThatThrownBy(() -> service.create(request((short) 5), authorUserId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("already reviewed");
    }

    @Test
    void aReviewOfAListingThatDoesNotExistIsRefused() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() ->
                service.create(new CreateReviewRequest(unknown, (short) 5, null), authorUserId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── the average ──────────────────────────────────────────────────────────

    @Test
    void theAverageCountsVerifiedReviewsAndRoundsToOnePlace() {
        when(reviewRepo.aggregateVerified(listingRecordId)).thenReturn(
                Optional.of(new ReviewRepository.Aggregate(4.7999999999999998, 62L)));

        ListingRatingResponse rating = service.rating(listingRecordId);

        assertThat(rating.average()).isEqualTo(4.8);
        assertThat(rating.count()).isEqualTo(62L);
    }

    @Test
    void aListingNobodyVerifiedHasReviewedIsUnratedRatherThanZero() {
        // "Unrated" and "rated badly" are different claims and have to render differently. A real
        // average of zero cannot occur — the rating floor is 1.
        when(reviewRepo.aggregateVerified(listingRecordId))
                .thenReturn(Optional.of(new ReviewRepository.Aggregate(null, 0L)));

        ListingRatingResponse rating = service.rating(listingRecordId);

        assertThat(rating.average()).isNull();
        assertThat(rating.count()).isZero();
    }

    // ── the aggregate's journey onto a card ──────────────────────────────────

    @Test
    void averifiedReviewPushesTheRecomputedAggregateToListingService() {
        when(bookingRepo.findFirstByConsumerUserIdAndListingRecordIdAndStatusOrderByUpdatedAtDesc(
                authorUserId, listingRecordId, BookingStatus.COMPLETED))
                .thenReturn(Optional.of(completedBooking()));
        when(reviewRepo.aggregateVerified(listingRecordId))
                .thenReturn(Optional.of(new ReviewRepository.Aggregate(4.5, 2L)));

        service.create(request((short) 4), authorUserId);

        // listing-service puts it on the snapshot and republishes, which is how it reaches the
        // search index and therefore a marketplace card.
        verify(listingClient).applyRating(listingRecordId, new java.math.BigDecimal("4.5"), 2L);
    }

    @Test
    void anUnverifiedReviewPushesNothing() {
        // It cannot move the average, so the round trip would recompute the same two numbers.
        service.create(request((short) 1), authorUserId);

        verify(listingClient, never()).applyRating(any(), any(), anyLong());
    }

    // ── the right of reply ───────────────────────────────────────────────────

    @Test
    void theVendorTheReviewIsAboutMayReply() {
        Review review = new Review();
        review.setId(UUID.randomUUID());
        review.setVendorId(vendorId);
        when(reviewRepo.findById(review.getId())).thenReturn(Optional.of(review));
        when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResponse resp = service.reply(review.getId(),
                new VendorReplyRequest("Sorry about the wait — we were short-staffed."),
                UUID.randomUUID(), vendorId);

        assertThat(resp.vendorReply()).startsWith("Sorry about the wait");
        assertThat(resp.vendorRepliedAt()).isNotNull();
    }

    @Test
    void anotherVendorMayNotReplyToSomebodyElsesReview() {
        Review review = new Review();
        review.setId(UUID.randomUUID());
        review.setVendorId(vendorId);
        when(reviewRepo.findById(review.getId())).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.reply(review.getId(),
                new VendorReplyRequest("Nothing to do with us."),
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aConsumerWithNoVendorContextMayNotReply() {
        Review review = new Review();
        review.setId(UUID.randomUUID());
        review.setVendorId(vendorId);
        when(reviewRepo.findById(review.getId())).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.reply(review.getId(),
                new VendorReplyRequest("I am the customer."), authorUserId, null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
