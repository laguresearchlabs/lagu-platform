package com.lagu.platform.automation.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@Slf4j
public class RecordServiceClient {

    private final RestClient restClient;

    public RecordServiceClient(RestClient.Builder loadBalancedRestClientBuilder) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://record-service")
                .defaultHeader("X-Internal-Service", "automation-service")
                .defaultHeader("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .build();
    }

    public Map<String, Object> updateRecord(String recordId, String orgId, Map<String, Object> data) {
        try {
            return restClient.patch()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Org-Id", orgId)
                    .body(Map.of("data", data))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to update record {}: {}", recordId, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> createRecord(String orgId, String objectType, Map<String, Object> data) {
        try {
            return restClient.post()
                    .uri("/api/v1/records")
                    .header("X-Org-Id", orgId)
                    .body(Map.of("objectType", objectType, "data", data))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to create record of type {}: {}", objectType, e.getMessage());
            return null;
        }
    }

    public void revokeVerification(String recordId, String notes) {
        try {
            restClient.post()
                    .uri("/api/v1/records/{id}/verification/revoke", recordId)
                    .body(Map.of("notes", notes != null ? notes : "Automated revocation"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to revoke verification for record {}: {}", recordId, e.getMessage());
        }
    }

    public void expireOverdueVerifications() {
        try {
            restClient.post()
                    .uri("/api/v1/records/verification/expire-overdue")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to trigger expire-overdue verifications: {}", e.getMessage());
        }
    }

    public void requestStatusTransition(String recordId, String orgId, String triggerName, String comment) {
        try {
            restClient.post()
                    .uri("/api/v1/records/{id}/transition", recordId)
                    .header("X-Org-Id", orgId)
                    .body(Map.of("triggerName", triggerName, "comment", comment != null ? comment : "Automated transition"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to request status transition for record {}: {}", recordId, e.getMessage());
        }
    }
}
