package com.lagu.platform.listing.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.events.AnalyticsEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.domain.ListingSnapshotRepository;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Records that someone looked at a listing.
 *
 * <p>The rest of the marketplace funnel is already durable server-side: inquiry, quote, confirmation
 * and completion are all booking rows, and {@code SettlementSummary} counts them directly. The view
 * step is the one nobody writes down, and it is where the largest drop-off lives - how many people
 * saw a listing and never enquired is not answerable without it.
 *
 * <p>Staged in the same outbox as every other listing event rather than written straight to
 * OpenSearch. That costs a little latency and buys the property that matters: a burst of views
 * cannot push back on the request that generated them, and nothing is lost if the indexer is down.
 *
 * <p>Unauthenticated on purpose - most listing views are by signed-out visitors, and a funnel that
 * only counted logged-in ones would measure the wrong population. It is therefore an endpoint
 * anyone can post to, which shapes what it accepts: the tenant and object type are read from the
 * snapshot rather than the request, an unknown listing id is refused, and there is no properties
 * bag to stuff. The worst a caller can do is inflate a view count for a listing that exists.
 *
 * <p>The id sits after a literal {@code /views} segment rather than reading
 * {@code /listings/{id}/viewed}, which would be the more natural REST shape. ServiceSecurityConfig
 * matches public paths by prefix, so an id in the middle of the path cannot be expressed as one -
 * and the alternative was making every service's security config understand patterns for the sake
 * of a single endpoint.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/listings")
@RequiredArgsConstructor
public class ListingViewController {

    private final ListingSnapshotRepository snapshotRepo;
    private final TransactionalOutbox outbox;

    public record ViewRequest(
            @Size(max = 64) String sessionId,
            @Size(max = 32) String source
    ) {}

    @PostMapping("/views/{recordId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> viewed(
            @PathVariable UUID recordId,
            @RequestBody(required = false) ViewRequest req) {

        // Only a published listing can be viewed, so an id that resolves to nothing is either a
        // stale link or a fabricated call. Either way there is nothing worth counting.
        ListingSnapshot snap = snapshotRepo.findByRecordId(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("ListingSnapshot", recordId.toString()));

        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID viewer = ctx != null ? ctx.getUserId() : null;

        AnalyticsEvent event = AnalyticsEvent.builder()
                .eventType("LISTING_VIEWED")
                .subjectId(recordId)
                // From the snapshot, not the request. A caller cannot attribute a view to someone
                // else's org, which would let anyone pollute a competitor's numbers.
                .tenantId(snap.getTenantId())
                .objectType(snap.getObjectType())
                .viewerUserId(viewer)
                .sessionId(req == null ? null : req.sessionId())
                .source(req == null ? null : req.source())
                .occurredAt(Instant.now())
                .build();

        outbox.stage(PlatformTopics.ANALYTICS_EVENTS, snap.getTenantId() + ":" + recordId, event);
        return ResponseEntity.accepted().body(ApiResponse.ok(null));
    }
}
