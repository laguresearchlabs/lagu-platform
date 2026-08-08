package com.lagu.platform.search.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.search.service.IndexMappingBuilder;
import com.lagu.platform.search.service.ReindexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchDocumentIndexer {

    private final OpenSearchClient     osClient;
    private final IndexMappingBuilder  mappingBuilder;
    private final ReindexService       reindexService;

    @KafkaListener(
            topics = PlatformTopics.RECORD_EVENTS,
            groupId = "search-service",
            properties = {"spring.json.value.default.type=com.lagu.platform.events.RecordEvent"}
    )
    public void handle(RecordEvent event, Acknowledgment ack) throws IOException {
        // Every branch resolves an index name from tenantId + objectType and uses recordId as the
        // document id, so a null in any of the three is unindexable no matter how often we retry.
        // Drop it with a warning rather than letting the NPE burn 3 retries and a DLT slot on a
        // record we could never process.
        if (event.getTenantId() == null || event.getObjectType() == null || event.getRecordId() == null) {
            log.warn("Skipping {} event: missing recordId={} tenantId={} objectType={}",
                    event.getEventType(), event.getRecordId(), event.getTenantId(), event.getObjectType());
            ack.acknowledge();
            return;
        }

        switch (event.getEventType()) {
            case "CREATED", "UPDATED" -> indexFull(event);
            case "STATUS_CHANGED"     -> partialUpdate(event);
            case "DELETED"            -> delete(event);
            default -> { /* ignore */ }
        }
        ack.acknowledge();
    }

    private void indexFull(RecordEvent event) throws IOException {
        String tenantId      = event.getTenantId().toString();
        String objectType = event.getObjectType();
        String recordId   = event.getRecordId().toString();

        mappingBuilder.ensureIndex(tenantId, objectType);
        String index = mappingBuilder.indexName(tenantId, objectType);

        Map<String, Object> doc = new HashMap<>();
        doc.put("recordId",   recordId);
        doc.put("tenantId",      tenantId);
        doc.put("objectType", objectType);
        doc.put("status",     event.getCurrentStatus());
        doc.put("data",       event.getData() != null ? event.getData() : Map.of());
        doc.put("createdAt",  Instant.now().toString());
        doc.put("updatedAt",  Instant.now().toString());

        osClient.index(r -> r.index(index).id(recordId).document(doc));
        log.debug("Indexed record {} into {}", recordId, index);
    }

    private void partialUpdate(RecordEvent event) throws IOException {
        String tenantId      = event.getTenantId().toString();
        String objectType = event.getObjectType();
        String recordId   = event.getRecordId().toString();

        // Unlike indexFull() this path used to skip ensureIndex, so a STATUS_CHANGED arriving
        // before any CREATED for that org/type (a fresh index, or a consumer replaying from
        // earliest) hit index_not_found_exception — a 404 that no amount of retrying can fix.
        mappingBuilder.ensureIndex(tenantId, objectType);
        String index = mappingBuilder.indexName(tenantId, objectType);

        Map<String, Object> patch = Map.of(
                "status",    event.getCurrentStatus(),
                "updatedAt", Instant.now().toString()
        );

        final String idx = index;
        final String id  = recordId;
        try {
            osClient.update(r -> r.index(idx).id(id).doc(patch), Map.class);
            log.debug("Partial-updated status for record {} in {}", recordId, index);
        } catch (OpenSearchException e) {
            if (!isDocumentMissing(e)) throw e;
            // The index exists but the document does not — the record's CREATED event aged out of
            // Kafka (retention is 168h) before this consumer group replayed. A partial update has
            // nothing to patch and no retry can change that, so rebuild the document from
            // record-service instead of dead-lettering a status change that is perfectly valid.
            if (reindexService.reindexOne(recordId, tenantId, objectType)) {
                log.info("Rebuilt missing document {} in {} from record-service", recordId, index);
            } else {
                log.warn("STATUS_CHANGED for record {}, absent from both {} and record-service; skipping",
                        recordId, index);
            }
        }
    }

    /** A 404 the update API returns when the index is present but the document id is not. */
    private static boolean isDocumentMissing(OpenSearchException e) {
        return e.status() == 404
                && e.error() != null
                && "document_missing_exception".equals(e.error().type());
    }

    private void delete(RecordEvent event) throws IOException {
        String tenantId      = event.getTenantId().toString();
        String objectType = event.getObjectType();
        String recordId   = event.getRecordId().toString();

        // Same gap as partialUpdate: deleting from an index that was never created threw
        // index_not_found_exception instead of being the no-op it should be.
        mappingBuilder.ensureIndex(tenantId, objectType);
        String index = mappingBuilder.indexName(tenantId, objectType);

        try {
            osClient.delete(r -> r.index(index).id(recordId));
            log.debug("Deleted record {} from {}", recordId, index);
        } catch (OpenSearchException e) {
            if (e.status() != 404) throw e;
            // Removing something that was never indexed is the outcome this event wanted anyway.
            // Dead-lettering it would be pure noise.
            log.debug("Record {} already absent from {}", recordId, index);
        }
    }
}
