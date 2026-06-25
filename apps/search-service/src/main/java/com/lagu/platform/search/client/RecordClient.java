package com.lagu.platform.search.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class RecordClient {

    private final RestClient restClient;

    public RecordClient(@Value("${platform.record-service.url:http://localhost:8101}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Service", "search-service")
                .defaultHeader("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .build();
    }

    /** Returns page of records for the given objectType. Org header must be set per request. */
    public Map<String, Object> listRecords(String objectType, String orgId, int page, int size) {
        try {
            return restClient.get()
                    .uri("/api/v1/records?objectType={t}&page={p}&size={s}", objectType, page, size)
                    .header("X-Org-Id", orgId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to list records for {}/{}: {}", orgId, objectType, e.getMessage());
            return Map.of();
        }
    }
}
