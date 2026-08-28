package com.lagu.platform.search.event;

import com.lagu.platform.events.AnalyticsEvent;
import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lands the two browser-only funnel steps in OpenSearch, alongside everything else.
 *
 * <p>Keeping them here rather than in a third-party analytics tool is what makes the funnel one
 * query: LISTING_VIEWED and QUOTE_VIEWED sit next to the booking events they lead to, so "how many
 * people saw this listing and never enquired" is answerable without stitching two systems together
 * and hoping their definitions of a session agree.
 *
 * <p>Indexed by month rather than as one growing index. View events are the highest-volume thing
 * the platform will ever write and they are only ever queried by time range, so a monthly index
 * makes retention a matter of dropping an index instead of a delete-by-query across everything.
 *
 * <p>Document ids are generated, not derived. Every other consumer in this service uses the
 * entity id so a replayed event overwrites rather than duplicates; here a repeat genuinely is a
 * second view and must count twice. That does mean a Kafka redelivery double-counts - the right
 * trade for a metric, where losing views would bias the funnel and an occasional duplicate does not.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsEventConsumer {

    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("yyyy.MM").withZone(ZoneOffset.UTC);

    private final OpenSearchClient osClient;

    /**
     * Must match what AnalyticsIndexInitializer builds its template and retention pattern from.
     * This was hardcoded to "platform-analytics-" while the initializer derived it from config, so
     * any deployment running a non-default prefix would have written to indices the template did
     * not claim: dynamic mapping, and no retention policy attached.
     */
    @Value("${opensearch.index-prefix:platform}")
    private String indexPrefix;

    @KafkaListener(
            topics = PlatformTopics.ANALYTICS_EVENTS,
            groupId = "search-service-analytics",
            properties = {"spring.json.value.default.type=com.lagu.platform.events.AnalyticsEvent"}
    )
    public void handle(AnalyticsEvent event, Acknowledgment ack) throws IOException {
        if (event.getEventType() == null || event.getSubjectId() == null) {
            // Nothing to count and nothing a retry would fix.
            log.warn("Skipping malformed analytics event: type={} subject={}",
                    event.getEventType(), event.getSubjectId());
            ack.acknowledge();
            return;
        }

        Instant occurredAt = event.getOccurredAt() != null ? event.getOccurredAt() : Instant.now();

        Map<String, Object> doc = new HashMap<>();
        doc.put("eventType", event.getEventType());
        doc.put("subjectId", event.getSubjectId().toString());
        doc.put("occurredAt", occurredAt.toString());
        putIfPresent(doc, "tenantId", event.getTenantId());
        putIfPresent(doc, "viewerUserId", event.getViewerUserId());
        if (event.getObjectType() != null) doc.put("objectType", event.getObjectType());
        if (event.getSessionId() != null)  doc.put("sessionId", event.getSessionId());
        if (event.getSource() != null)     doc.put("source", event.getSource());

        String index = indexPrefix + "-analytics-" + MONTH.format(occurredAt);
        osClient.index(i -> i.index(index).document(doc));

        log.debug("Indexed {} for subject {} into {}", event.getEventType(), event.getSubjectId(), index);
        ack.acknowledge();
    }

    private void putIfPresent(Map<String, Object> doc, String key, UUID value) {
        if (value != null) {
            doc.put(key, value.toString());
        }
    }
}
