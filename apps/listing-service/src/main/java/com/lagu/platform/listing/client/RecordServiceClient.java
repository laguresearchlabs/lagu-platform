package com.lagu.platform.listing.client;

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

    public RecordServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://record-service")
                .defaultHeader("X-Internal-Service", "listing-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * Propagates HTTP failures rather than returning null: the Kafka consumer relies on the
     * exception to trigger retry/DLT, and a swallowed failure here would silently skip a
     * snapshot publication.
     */
    public Map<String, Object> getRecord(UUID recordId, UUID orgId) {
        return restClient.get()
                .uri("/api/v1/records/{id}", recordId)
                .header("X-Org-Id", orgId != null ? orgId.toString() : "")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
