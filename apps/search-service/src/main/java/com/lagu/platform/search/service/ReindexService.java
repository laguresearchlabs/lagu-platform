package com.lagu.platform.search.service;

import com.lagu.platform.search.client.RecordClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReindexService {

    private static final int PAGE_SIZE = 100;

    private final RecordClient        recordClient;
    private final OpenSearchClient    osClient;
    private final IndexMappingBuilder mappingBuilder;

    /**
     * Paginates through record-service and reindexes all records of the given objectType
     * for the given org. Runs asynchronously — the admin endpoint returns immediately.
     */
    @Async
    public void reindex(String objectType, String tenantId) {
        log.info("Starting reindex: org={} objectType={}", tenantId, objectType);
        mappingBuilder.ensureIndex(tenantId, objectType);
        String index = mappingBuilder.indexName(tenantId, objectType);

        int page = 0, indexed = 0;
        while (true) {
            Map<String, Object> resp = recordClient.listRecords(objectType, tenantId, page, PAGE_SIZE);
            if (resp == null || resp.isEmpty()) break;

            //noinspection unchecked
            Map<String, Object> data     = (Map<String, Object>) resp.get("data");
            if (data == null) break;
            //noinspection unchecked
            List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("content");
            if (records == null || records.isEmpty()) break;

            for (Map<String, Object> record : records) {
                try {
                    String recordId = String.valueOf(record.get("id"));
                    Map<String, Object> doc = buildDoc(record, tenantId, objectType);
                    osClient.index(r -> r.index(index).id(recordId).document(doc));
                    indexed++;
                } catch (IOException e) {
                    log.error("Failed to index record during reindex: {}", e.getMessage());
                }
            }

            boolean last = Boolean.TRUE.equals(data.get("last"));
            if (last) break;
            page++;
        }

        log.info("Reindex complete: org={} objectType={} indexed={}", tenantId, objectType, indexed);
    }

    /**
     * Rebuilds a single document from record-service. Used when a STATUS_CHANGED arrives for a
     * record whose CREATED event is no longer on the topic (Kafka retention is shorter than the
     * lifetime of a record), so the partial update has nothing to patch.
     *
     * <p>Deliberately synchronous, unlike {@link #reindex}: the caller is a Kafka listener that
     * must know whether the document exists before it acks.
     *
     * @return false if record-service says the record no longer exists — there is nothing to index
     */
    public boolean reindexOne(String recordId, String tenantId, String objectType) throws IOException {
        Map<String, Object> record = recordClient.getRecord(recordId, tenantId);
        if (record == null) return false;

        mappingBuilder.ensureIndex(tenantId, objectType);
        String index = mappingBuilder.indexName(tenantId, objectType);

        Map<String, Object> doc = buildDoc(record, tenantId, objectType);
        osClient.index(r -> r.index(index).id(recordId).document(doc));
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDoc(Map<String, Object> record, String tenantId, String objectType) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("recordId",   String.valueOf(record.get("id")));
        doc.put("tenantId",      tenantId);
        doc.put("objectType", objectType);
        doc.put("status",     record.get("status"));
        doc.put("data",       record.getOrDefault("data", Map.of()));
        doc.put("createdAt",  record.getOrDefault("createdAt", Instant.now().toString()));
        doc.put("updatedAt",  record.getOrDefault("updatedAt", Instant.now().toString()));
        return doc;
    }
}
