package com.lagu.platform.event.client;

import com.lagu.platform.common.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
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

    /**
     * One key to sign, paired with the record it belongs to. Many-to-one: a post's gallery signs
     * several keys that all belong to that post's record, and each must still travel with the
     * record id — it is what record-service checks the key against before signing.
     */
    public record MediaKey(UUID recordId, String key) {}

    /**
     * Signs media keys in bulk, so a feed of posts costs one call rather than one per post.
     *
     * <p>record-service does the signing because its bucket credential is the one IAM-scoped to
     * the {@code record/} prefix; giving event-service its own access to save a hop would dissolve
     * that boundary for something that happens once per page.
     *
     * @return signed URL per key; a key that could not be signed is simply absent
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> signMediaKeys(java.util.Collection<MediaKey> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();

        List<Map<String, String>> items = keys.stream()
                .filter(k -> k.recordId() != null && k.key() != null)
                .distinct()
                .map(k -> Map.of("recordId", k.recordId().toString(), "key", k.key()))
                .toList();
        if (items.isEmpty()) return Map.of();

        try {
            Map<String, Object> envelope = restClient.post()
                    .uri("/internal/records/media/sign")
                    .body(Map.<String, Object>of("items", items))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (envelope == null || !(envelope.get("data") instanceof Map)) {
                log.warn("Unexpected response signing {} media key(s) for posts", items.size());
                return Map.of();
            }
            return (Map<String, String>) envelope.get("data");
        } catch (Exception e) {
            // Photos are decoration on a feed; a post without its images still reads. Failing the
            // whole feed because signing was unavailable would turn that into an outage.
            log.warn("Could not sign {} media key(s) for posts: {}", items.size(), e.getMessage());
            return Map.of();
        }
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

    public Map<String, Object> updateRecord(UUID recordId, UUID tenantId, UUID actingUserId,
                                             Map<String, Object> data) {
        try {
            return restClient.put()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Tenant-Id", tenantId.toString())
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
     * Records of objectType belonging to tenantId, newest first. A null status excludes only
     * DELETED (record-service's default); a non-null status filters to exactly that status.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listRecords(UUID tenantId, String objectType, String status, int page, int size) {
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
                    .header("X-Tenant-Id", tenantId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return List.of();
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> pageMap)) return List.of();
            Object content = ((Map<String, Object>) pageMap).get("content");
            return content instanceof List<?> ? (List<Map<String, Object>>) content : List.of();
        } catch (Exception e) {
            log.warn("Failed to list {} records for org {}: {}", objectType, tenantId, e.getMessage());
            return List.of();
        }
    }

    /** Server-side partial merge (record-service's PATCH), unlike updateRecord's full replace. */
    public Map<String, Object> patchRecord(UUID recordId, UUID tenantId, UUID actingUserId,
                                            Map<String, Object> partialData) {
        try {
            return restClient.patch()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("X-User-Id", actingUserId.toString())
                    .body(partialData)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to patch record {}: {}", recordId, e.getMessage());
            return null;
        }
    }

    public void deleteRecord(UUID recordId, UUID tenantId) {
        restClient.delete()
                .uri("/api/v1/records/{id}", recordId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * POSTs a workflow trigger; record-service stages it via the outbox and processes it async.
     *
     * <p>Failures propagate rather than being swallowed. A dropped trigger leaves the record in
     * its previous status indefinitely — for a post that means DRAFT, which never appears in any
     * listing — and returning null here made that indistinguishable from success, so the caller
     * happily reported a published post that did not exist. Note this only covers the request
     * being accepted: the transition itself completes asynchronously in workflow-service, so a
     * success here is not a guarantee that the status has changed yet.
     */
    public Map<String, Object> requestTransition(UUID recordId, UUID tenantId, UUID actingUserId, String trigger) {
        try {
            return restClient.post()
                    .uri("/api/v1/records/{id}/status", recordId)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("X-User-Id", actingUserId.toString())
                    .body(Map.of("trigger", trigger))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to request transition '{}' on record {}: {}", trigger, recordId, e.getMessage());
            throw new PlatformException("TRANSITION_FAILED",
                    "Could not request the '" + trigger + "' transition", HttpStatus.BAD_GATEWAY);
        }
    }

    public void createRelationship(UUID sourceRecordId, UUID tenantId, UUID actingUserId,
                                    String relationshipName, UUID targetRecordId) {
        restClient.post()
                .uri("/api/v1/records/{sourceId}/relationships", sourceRecordId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", actingUserId.toString())
                .body(Map.of("relationshipName", relationshipName, "targetRecordId", targetRecordId))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public void deleteRelationship(UUID sourceRecordId, UUID tenantId, String relationshipName, UUID targetRecordId) {
        restClient.delete()
                .uri("/api/v1/records/{sourceId}/relationships/{relName}/{targetId}",
                        sourceRecordId, relationshipName, targetRecordId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .toBodilessEntity();
    }

    public List<Map<String, Object>> listRelationships(UUID sourceRecordId, UUID tenantId) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/records/{sourceId}/relationships", sourceRecordId)
                    .header("X-Tenant-Id", tenantId.toString())
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
