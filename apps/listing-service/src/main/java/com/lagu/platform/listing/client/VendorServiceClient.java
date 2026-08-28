package com.lagu.platform.listing.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A vendor org's review status, for the publish gate.
 *
 * <p><b>Fails closed.</b> Unlike the best-effort lookups elsewhere in the platform, an unknown
 * answer here must not publish: this is the control that keeps an unverified business off the
 * consumer marketplace, and a control that opens under load is not a control. A vendor-service
 * outage therefore holds publishes rather than waving them through — and because a held snapshot
 * is reconciled when the org is next activated, nothing is lost by waiting.
 */
@Component
@Slf4j
public class VendorServiceClient {

    private final RestClient restClient;

    public VendorServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://vendor-service")
                .defaultHeader("X-Internal-Service", "listing-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * The org's review status, or empty when it could not be determined — an unknown org, an
     * unreachable vendor-service, or an unexpected response shape. Callers must treat empty as
     * "not permitted to publish", never as "probably fine".
     */
    public Optional<String> findStatus(UUID tenantId) {
        if (tenantId == null) return Optional.empty();
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/internal/vendors/{tenantId}/status", tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
                return Optional.empty();
            }
            Object status = data.get("status");
            return status != null ? Optional.of(status.toString()) : Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read vendor status for org {}: {} — publishing is held, not allowed",
                    tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /** True only when the org is positively known to be ACTIVE. */
    public boolean isActive(UUID tenantId) {
        return findStatus(tenantId).filter("ACTIVE"::equals).isPresent();
    }
}
