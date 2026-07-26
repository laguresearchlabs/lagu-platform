package com.lagu.platform.listing.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
                .defaultHeader("X-Internal-Service", "listing-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    public record ListingTypeFlags(boolean publishable, boolean consumerSearchable) {}

    /**
     * Whether this objectType is a publishable/searchable listing at all — replaces a
     * hardcoded allowlist that used to live in ListingSnapshotService and silently drifted out
     * of sync with schema-registry's own ListingTypeDefinition.publishable/consumerSearchable
     * flags (e.g. event record types were never added to it). Defaults to "not publishable" on
     * any lookup failure — fail closed, since this gates whether a record's data becomes a
     * public, cross-org snapshot.
     */
    @SuppressWarnings("unchecked")
    public ListingTypeFlags getFlags(String objectType) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/listing-types/{name}", objectType.toUpperCase())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return new ListingTypeFlags(false, false);
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> m)) return new ListingTypeFlags(false, false);
            Map<String, Object> def = (Map<String, Object>) m;
            boolean publishable = Boolean.TRUE.equals(def.get("publishable"));
            boolean consumerSearchable = Boolean.TRUE.equals(def.get("consumerSearchable"));
            return new ListingTypeFlags(publishable, consumerSearchable);
        } catch (Exception e) {
            log.warn("Failed to fetch listing type flags for {}: {}", objectType, e.getMessage());
            return new ListingTypeFlags(false, false);
        }
    }
}
