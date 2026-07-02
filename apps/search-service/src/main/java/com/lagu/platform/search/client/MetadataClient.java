package com.lagu.platform.search.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches listing-type schemas from schema-registry (metadata-service's schema responsibilities
 * were absorbed into schema-registry — see todo/13-no-code-vendor-platform-adr.md). Flattens
 * schema-registry's section-nested response into the same flat {name, type, ...} map shape
 * IndexMappingBuilder already expects, so IndexMappingBuilder itself needed no changes.
 */
@Component
@Slf4j
public class MetadataClient {

    public static final String SCHEMA_CACHE = "search:schema";

    private final RestClient restClient;

    public MetadataClient(
            RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://schema-registry")
                .defaultHeader("X-Internal-Service", "search-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .defaultHeader("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .build();
    }

    @Cacheable(SCHEMA_CACHE)
    public List<Map<String, Object>> getSchema(String objectType) {
        log.info("Fetching schema for objectType={}", objectType);
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/listing-types/{name}/schema", objectType)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null) return List.of();
            Map<?, ?> data = (Map<?, ?>) response.get("data");
            if (data == null) return List.of();

            //noinspection unchecked
            List<Map<String, Object>> sections = (List<Map<String, Object>>) data.get("sections");
            if (sections == null) return List.of();

            List<Map<String, Object>> fields = new ArrayList<>();
            for (Map<String, Object> section : sections) {
                //noinspection unchecked
                List<Map<String, Object>> sectionFields = (List<Map<String, Object>>) section.get("fields");
                if (sectionFields == null) continue;
                for (Map<String, Object> f : sectionFields) {
                    Map<String, Object> flattened = new java.util.HashMap<>(f);
                    flattened.put("name", f.get("key"));
                    flattened.put("type", f.get("fieldType"));
                    fields.add(flattened);
                }
            }
            return fields;
        } catch (Exception e) {
            log.error("Failed to fetch schema for {}: {}", objectType, e.getMessage());
            return List.of();
        }
    }
}
