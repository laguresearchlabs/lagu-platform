package com.lagu.platform.search.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class MetadataClient {

    public static final String SCHEMA_CACHE = "search:schema";

    private final RestClient restClient;

    public MetadataClient(RestClient.Builder loadBalancedRestClientBuilder) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://metadata-service")
                .defaultHeader("X-Internal-Service", "search-service")
                .defaultHeader("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .build();
    }

    @Cacheable(SCHEMA_CACHE)
    public List<Map<String, Object>> getSchema(String objectType) {
        log.info("Fetching schema for objectType={}", objectType);
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/object-types/by-name/{name}/schema", objectType)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null) return List.of();
            Map<?, ?> data = (Map<?, ?>) response.get("data");
            if (data == null) return List.of();
            //noinspection unchecked
            return (List<Map<String, Object>>) data.get("fields");
        } catch (Exception e) {
            log.error("Failed to fetch schema for {}: {}", objectType, e.getMessage());
            return List.of();
        }
    }
}
