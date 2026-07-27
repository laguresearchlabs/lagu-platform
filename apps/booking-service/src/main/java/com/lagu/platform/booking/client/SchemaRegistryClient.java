package com.lagu.platform.booking.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

@Component
@Slf4j
public class SchemaRegistryClient {

    private final RestClient restClient;

    public SchemaRegistryClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://schema-registry")
                .defaultHeader("X-Internal-Service", "booking-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * Unlike listing-service's {@code SchemaRegistryClient.getFlags} (fail-closed to a safe
     * default on any error, since that only gates search/publish visibility), a failed commission
     * lookup here must NOT default to zero or skip the field — quoting a booking with a silently
     * wrong commission rate is a financial-correctness bug, not a visibility bug. Any failure
     * (network, 4xx, 5xx, missing field) throws so the Quote request fails loudly (502/503)
     * instead of succeeding with bad data.
     */
    @SuppressWarnings("unchecked")
    public BigDecimal getCommissionRate(String tierName, String listingType) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/tier-configs/{tierName}?listingType={listingType}", tierName, listingType)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Object data = response != null ? response.get("data") : null;
            if (!(data instanceof Map<?, ?> m)) {
                throw new IllegalStateException("No tier-config data returned for tier " + tierName);
            }
            Object commissionRate = ((Map<String, Object>) m).get("commissionRate");
            if (commissionRate == null) {
                throw new IllegalStateException("Tier-config for " + tierName + " has no commissionRate");
            }
            return new BigDecimal(commissionRate.toString());
        } catch (Exception e) {
            log.error("Failed to fetch commission rate for tier {} listingType {}: {}",
                    tierName, listingType, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not resolve commission rate — try again", e);
        }
    }
}
