package com.lagu.platform.booking.api;

import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.booking.domain.BookingRepository;
import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.events.AnalyticsEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Records that the consumer opened a quote.
 *
 * <p>The second half of the funnel gap. QUOTED and CONFIRMED are both booking rows, so the drop
 * between them is already countable - but it conflates two very different failures: a quote nobody
 * ever looked at, and a quote that was read and rejected. The first is a notification problem, the
 * second is a pricing problem, and without this event they are the same number.
 *
 * <p>Unlike a listing view this is not anonymous. A quote belongs to one consumer, so the endpoint
 * requires a session and refuses anyone who is not a party to the booking - otherwise anybody
 * holding a booking id could inflate a vendor's "quotes read" figure, and the vendor would draw
 * conclusions from it.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class QuoteViewController {

    private final BookingRepository bookingRepo;
    private final TransactionalOutbox outbox;

    public record ViewRequest(
            @Size(max = 64) String sessionId,
            @Size(max = 32) String source
    ) {}

    @PostMapping("/{bookingId}/quote-viewed")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> quoteViewed(
            @PathVariable UUID bookingId,
            @RequestBody(required = false) ViewRequest req) {

        Booking booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId.toString()));

        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID viewer = ctx != null ? ctx.getUserId() : null;
        if (viewer == null || !viewer.equals(booking.getConsumerUserId())) {
            // The vendor opening their own quote is not a signal about the consumer, so it is not
            // counted either - this is specifically "the person who has to decide has now seen it".
            throw new AccessDeniedException("Only the consumer on this booking can record a quote view");
        }

        AnalyticsEvent event = AnalyticsEvent.builder()
                .eventType("QUOTE_VIEWED")
                .subjectId(bookingId)
                .tenantId(booking.getVendorId())
                .viewerUserId(viewer)
                .sessionId(req == null ? null : req.sessionId())
                .source(req == null ? null : req.source())
                .occurredAt(Instant.now())
                .build();

        outbox.stage(PlatformTopics.ANALYTICS_EVENTS, booking.getVendorId() + ":" + bookingId, event);
        return ResponseEntity.accepted().body(ApiResponse.ok(null));
    }
}
