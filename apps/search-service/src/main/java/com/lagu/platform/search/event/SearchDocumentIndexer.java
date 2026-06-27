package com.lagu.platform.search.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.search.service.IndexMappingBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
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

    @KafkaListener(
            topics = PlatformTopics.RECORD_EVENTS,
            groupId = "search-service",
            properties = {"spring.json.value.default.type=com.lagu.platform.events.RecordEvent"}
    )
    public void handle(RecordEvent event, Acknowledgment ack) throws IOException {
        switch (event.getEventType()) {
            case "CREATED", "UPDATED" -> indexFull(event);
            case "STATUS_CHANGED"     -> partialUpdate(event);
            case "DELETED"            -> delete(event);
            default -> { /* ignore */ }
        }
        ack.acknowledge();
    }

    private void indexFull(RecordEvent event) throws IOException {
        String orgId      = event.getOrgId().toString();
        String objectType = event.getObjectType();
        String recordId   = event.getRecordId().toString();

        mappingBuilder.ensureIndex(orgId, objectType);
        String index = mappingBuilder.indexName(orgId, objectType);

        Map<String, Object> doc = new HashMap<>();
        doc.put("recordId",   recordId);
        doc.put("orgId",      orgId);
        doc.put("objectType", objectType);
        doc.put("status",     event.getCurrentStatus());
        doc.put("data",       event.getData() != null ? event.getData() : Map.of());
        doc.put("createdAt",  Instant.now().toString());
        doc.put("updatedAt",  Instant.now().toString());

        osClient.index(r -> r.index(index).id(recordId).document(doc));
        log.debug("Indexed record {} into {}", recordId, index);
    }

    private void partialUpdate(RecordEvent event) throws IOException {
        String orgId      = event.getOrgId().toString();
        String objectType = event.getObjectType();
        String recordId   = event.getRecordId().toString();
        String index      = mappingBuilder.indexName(orgId, objectType);

        Map<String, Object> patch = Map.of(
                "status",    event.getCurrentStatus(),
                "updatedAt", Instant.now().toString()
        );

        final String idx = index;
        final String id  = recordId;
        osClient.update(r -> r.index(idx).id(id).doc(patch), Map.class);
        log.debug("Partial-updated status for record {} in {}", recordId, index);
    }

    private void delete(RecordEvent event) throws IOException {
        String orgId      = event.getOrgId().toString();
        String objectType = event.getObjectType();
        String recordId   = event.getRecordId().toString();
        String index      = mappingBuilder.indexName(orgId, objectType);

        osClient.delete(r -> r.index(index).id(recordId));
        log.debug("Deleted record {} from {}", recordId, index);
    }
}
