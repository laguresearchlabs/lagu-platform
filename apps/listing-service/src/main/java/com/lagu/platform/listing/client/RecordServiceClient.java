package com.lagu.platform.listing.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class RecordServiceClient {

    private final RestClient restClient;

    public RecordServiceClient(
            @Value("${platform.record-service.url:http://record-service:8101}") String url) {
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .defaultHeader("X-Internal-Service", "listing-service")
                .defaultHeader("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .build();
    }

    public Map<String, Object> getRecord(UUID recordId, UUID orgId) {
        try {
            return restClient.get()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Org-Id", orgId != null ? orgId.toString() : "")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to fetch record {}: {}", recordId, e.getMessage());
            return null;
        }
    }
}
