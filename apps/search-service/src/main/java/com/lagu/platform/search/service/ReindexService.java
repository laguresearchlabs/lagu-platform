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
    public void reindex(String objectType, String orgId) {
        log.info("Starting reindex: org={} objectType={}", orgId, objectType);
        mappingBuilder.ensureIndex(orgId, objectType);
        String index = mappingBuilder.indexName(orgId, objectType);

        int page = 0, indexed = 0;
        while (true) {
            Map<String, Object> resp = recordClient.listRecords(objectType, orgId, page, PAGE_SIZE);
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
                    Map<String, Object> doc = buildDoc(record, orgId, objectType);
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

        log.info("Reindex complete: org={} objectType={} indexed={}", orgId, objectType, indexed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDoc(Map<String, Object> record, String orgId, String objectType) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("recordId",   String.valueOf(record.get("id")));
        doc.put("orgId",      orgId);
        doc.put("objectType", objectType);
        doc.put("status",     record.get("status"));
        doc.put("data",       record.getOrDefault("data", Map.of()));
        doc.put("createdAt",  record.getOrDefault("createdAt", Instant.now().toString()));
        doc.put("updatedAt",  record.getOrDefault("updatedAt", Instant.now().toString()));
        return doc;
    }
}
