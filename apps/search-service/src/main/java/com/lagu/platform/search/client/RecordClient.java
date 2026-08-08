package com.lagu.platform.search.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class RecordClient {

    private final RestClient restClient;

    public RecordClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://record-service")
                .defaultHeader("X-Internal-Service", "search-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * Fetches a single record for self-healing a search document whose CREATED event has aged out
     * of Kafka. Returns null only when record-service confirms the record does not exist (404) —
     * every other failure propagates, because a transient record-service outage must not be
     * mistaken for a deleted record and silently swallow the update.
     */
    public Map<String, Object> getRecord(String recordId, String tenantId) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return null;
            //noinspection unchecked
            return (Map<String, Object>) response.get("data");
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Record {} not found in record-service", recordId);
            return null;
        }
    }

    /** Returns page of records for the given objectType. Org header must be set per request. */
    public Map<String, Object> listRecords(String objectType, String tenantId, int page, int size) {
        try {
            return restClient.get()
                    .uri("/api/v1/records?objectType={t}&page={p}&size={s}", objectType, page, size)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to list records for {}/{}: {}", tenantId, objectType, e.getMessage());
            return Map.of();
        }
    }
}
