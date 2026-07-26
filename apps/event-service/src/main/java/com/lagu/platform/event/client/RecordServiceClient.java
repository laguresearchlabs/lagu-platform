package com.lagu.platform.event.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class RecordServiceClient {

    private final RestClient restClient;

    public RecordServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        // Identity: SVC_EVENT_SERVICE via X-Internal-Service — the acting user/org travel as
        // per-request headers so record-service scopes tenancy and attributes audit correctly.
        // Real authorization for an event happens one layer up, in EventService's own
        // EventMember role check, before any of these methods are ever called.
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://record-service")
                .defaultHeader("X-Internal-Service", "event-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    public Map<String, Object> createRecord(UUID orgId, UUID actingUserId, String objectType,
                                             Map<String, Object> data) {
        try {
            return restClient.post()
                    .uri("/api/v1/records")
                    .header("X-Org-Id", orgId.toString())
                    .header("X-User-Id", actingUserId.toString())
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

    public Map<String, Object> updateRecord(UUID recordId, UUID orgId, UUID actingUserId,
                                             Map<String, Object> data) {
        try {
            return restClient.put()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Org-Id", orgId.toString())
                    .header("X-User-Id", actingUserId.toString())
                    .body(Map.of("data", data))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to update record {}: {}", recordId, e.getMessage());
            return null;
        }
    }

    /**
     * Records of objectType belonging to orgId, newest first. A null status excludes only
     * DELETED (record-service's default); a non-null status filters to exactly that status.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listRecords(UUID orgId, String objectType, String status, int page, int size) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/api/v1/records")
                                .queryParam("objectType", objectType)
                                .queryParam("page", page)
                                .queryParam("size", size);
                        if (status != null) b = b.queryParam("status", status);
                        return b.build();
                    })
                    .header("X-Org-Id", orgId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return List.of();
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> pageMap)) return List.of();
            Object content = ((Map<String, Object>) pageMap).get("content");
            return content instanceof List<?> ? (List<Map<String, Object>>) content : List.of();
        } catch (Exception e) {
            log.warn("Failed to list {} records for org {}: {}", objectType, orgId, e.getMessage());
            return List.of();
        }
    }

    /** Server-side partial merge (record-service's PATCH), unlike updateRecord's full replace. */
    public Map<String, Object> patchRecord(UUID recordId, UUID orgId, UUID actingUserId,
                                            Map<String, Object> partialData) {
        try {
            return restClient.patch()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Org-Id", orgId.toString())
                    .header("X-User-Id", actingUserId.toString())
                    .body(partialData)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to patch record {}: {}", recordId, e.getMessage());
            return null;
        }
    }

    public void deleteRecord(UUID recordId, UUID orgId) {
        restClient.delete()
                .uri("/api/v1/records/{id}", recordId)
                .header("X-Org-Id", orgId.toString())
                .retrieve()
                .toBodilessEntity();
    }

    /** POSTs a workflow trigger; record-service stages it via the outbox and processes it async. */
    public Map<String, Object> requestTransition(UUID recordId, UUID orgId, UUID actingUserId, String trigger) {
        try {
            return restClient.post()
                    .uri("/api/v1/records/{id}/status", recordId)
                    .header("X-Org-Id", orgId.toString())
                    .header("X-User-Id", actingUserId.toString())
                    .body(Map.of("trigger", trigger))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to request transition '{}' on record {}: {}", trigger, recordId, e.getMessage());
            return null;
        }
    }

    public void createRelationship(UUID sourceRecordId, UUID orgId, UUID actingUserId,
                                    String relationshipName, UUID targetRecordId) {
        restClient.post()
                .uri("/api/v1/records/{sourceId}/relationships", sourceRecordId)
                .header("X-Org-Id", orgId.toString())
                .header("X-User-Id", actingUserId.toString())
                .body(Map.of("relationshipName", relationshipName, "targetRecordId", targetRecordId))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public void deleteRelationship(UUID sourceRecordId, UUID orgId, String relationshipName, UUID targetRecordId) {
        restClient.delete()
                .uri("/api/v1/records/{sourceId}/relationships/{relName}/{targetId}",
                        sourceRecordId, relationshipName, targetRecordId)
                .header("X-Org-Id", orgId.toString())
                .retrieve()
                .toBodilessEntity();
    }

    public List<Map<String, Object>> listRelationships(UUID sourceRecordId, UUID orgId) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/records/{sourceId}/relationships", sourceRecordId)
                    .header("X-Org-Id", orgId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return List.of();
            Object data = response.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = data instanceof List<?> ? (List<Map<String, Object>>) data : List.of();
            return list;
        } catch (Exception e) {
            log.warn("Failed to list relationships for record {}: {}", sourceRecordId, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public UUID extractRecordId(Map<String, Object> response) {
        try {
            if (response == null) return null;
            Object data = response.get("data");
            if (data instanceof Map<?, ?> m) {
                Object id = ((Map<String, Object>) m).get("id");
                return id != null ? UUID.fromString(id.toString()) : null;
            }
        } catch (Exception e) {
            log.warn("Could not extract recordId from response: {}", e.getMessage());
        }
        return null;
    }
}
