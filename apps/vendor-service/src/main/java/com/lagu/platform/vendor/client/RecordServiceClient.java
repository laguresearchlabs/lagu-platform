package com.lagu.platform.vendor.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        // Identity: SVC_VENDOR_SERVICE via X-Internal-Service — the acting user/org travel as
        // per-request headers so record-service scopes tenancy and attributes audit correctly.
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://record-service")
                .defaultHeader("X-Internal-Service", "vendor-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    public Map<String, Object> createRecord(UUID tenantId, UUID actingUserId, String objectType,
                                            Map<String, Object> data) {
        try {
            return restClient.post()
                    .uri("/api/v1/records")
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("X-User-Id", actingUserId.toString())
                    .body(Map.of("objectType", objectType, "data", data))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to create {} record for org {}: {}", objectType, tenantId, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getRecord(UUID recordId, UUID tenantId) {
        try {
            return restClient.get()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Tenant-Id", tenantId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to get record {}: {}", recordId, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getDocumentStatus(UUID tenantId, UUID userId) {
        try {
            return restClient.get()
                    .uri("/api/v1/documents/submission-status")
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("X-User-Id", userId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to get document status for org {}: {}", tenantId, e.getMessage());
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
