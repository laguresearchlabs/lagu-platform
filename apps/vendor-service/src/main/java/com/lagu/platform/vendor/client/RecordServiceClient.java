package com.lagu.platform.vendor.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecordServiceClient(
            @Value("${platform.record-service.url:http://record-service:8101}") String url) {
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .defaultHeader("X-Internal-Service", "vendor-service")
                .defaultHeader("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .build();
    }

    public Map<String, Object> createRecord(UUID orgId, String objectType, Map<String, Object> data) {
        try {
            return restClient.post()
                    .uri("/api/v1/records")
                    .header("X-Org-Id", orgId.toString())
                    .body(Map.of("objectType", objectType, "data", data))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to create {} record for org {}: {}", objectType, orgId, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getRecord(UUID recordId, UUID orgId) {
        try {
            return restClient.get()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Org-Id", orgId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to get record {}: {}", recordId, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getDocumentStatus(UUID orgId, UUID userId) {
        try {
            return restClient.get()
                    .uri("/api/v1/documents/submission-status")
                    .header("X-Org-Id", orgId.toString())
                    .header("X-User-Id", userId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to get document status for org {}: {}", orgId, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    public UUID extractRecordId(Map<String, Object> response) {
        try {
            if (response == null) return null;
            Object data = response.get("data");
            if (data instanceof Map<?,?> m) {
                Object id = ((Map<String, Object>) m).get("id");
                return id != null ? UUID.fromString(id.toString()) : null;
            }
        } catch (Exception e) {
            log.warn("Could not extract recordId from response: {}", e.getMessage());
        }
        return null;
    }
}
