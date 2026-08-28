package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A funnel step that only the browser can observe.
 *
 * <p>Most of the marketplace funnel is already a durable server-side fact: an inquiry, a quote, a
 * confirmation and a completion are all rows, and {@code SettlementSummary} counts them straight
 * out of the booking table. Two steps are not. Nobody writes a row when someone looks at a listing
 * or opens a quote, and those are precisely the steps that explain the biggest drop-offs - how many
 * people saw a listing and never enquired, how many read a quote and never accepted it.
 *
 * <p>These events go through the same outbox and Kafka path as everything else and land in
 * OpenSearch next to the rest, so the funnel is one query rather than two half-answers stitched
 * together from a third-party tool.
 *
 * <p><b>What is deliberately not here.</b> No IP address, no user agent, no device fingerprint, no
 * free-form properties bag. A view event needs to answer "how many people reached this step", and
 * every field beyond that is data we would have to justify holding. {@code viewerUserId} is null
 * for a signed-out visitor and stays null - the session id is enough to count a person twice
 * without knowing who they are.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalyticsEvent implements PlatformEvent {

    /** LISTING_VIEWED | QUOTE_VIEWED */
    private String eventType;

    /** The listing or booking that was looked at. */
    private UUID subjectId;

    /** Owning vendor org, so a vendor can be shown their own funnel. */
    private UUID tenantId;

    /** VENUE, CATERER, ... for LISTING_VIEWED; null for QUOTE_VIEWED. */
    private String objectType;

    /** Null when the viewer is not signed in, which is the common case for a listing view. */
    private UUID viewerUserId;

    /**
     * Opaque per-tab identifier minted by the client. Lets repeated views within one visit be
     * collapsed without identifying anyone; it is not stable across sessions by design.
     */
    private String sessionId;

    /** Where the view came from - SEARCH, DIRECT, SHARE_LINK - for attribution. */
    private String source;

    private Instant occurredAt;
}
