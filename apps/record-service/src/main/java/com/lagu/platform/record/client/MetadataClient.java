package com.lagu.platform.record.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataClient {

    public static final String SCHEMA_CACHE = "metadata-schema";

    private final RestClient metadataRestClient;
    private final ObjectMapper objectMapper;

    @Cacheable(value = SCHEMA_CACHE, key = "#objectTypeName")
    public ObjectTypeSchemaDto getSchema(String objectTypeName) {
        log.debug("Fetching schema for objectType={}", objectTypeName);
        ApiResponse<ObjectTypeSchemaDto> response = metadataRestClient.get()
                .uri("/api/v1/object-types/by-name/{name}/schema", objectTypeName)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new IllegalStateException("Schema not found for objectType: " + objectTypeName);
        }
        return response.getData();
    }

    public RelationshipDefinitionDto getRelationshipDefinition(String name) {
        log.debug("Fetching relationship definition name={}", name);
        try {
            ApiResponse<RelationshipDefinitionDto> response = metadataRestClient.get()
                    .uri("/api/v1/relationship-definitions/by-name/{name}", name)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return (response != null && response.isSuccess()) ? response.getData() : null;
        } catch (Exception e) {
            log.warn("Relationship definition '{}' not found in metadata-service: {}", name, e.getMessage());
            return null;
        }
    }

    public record ObjectTypeSchemaDto(String objectType, List<FieldSchemaDto> fields) {}

    public record FieldSchemaDto(
            String name,
            String label,
            String type,
            boolean required,
            boolean searchable,
            boolean filterable,
            boolean sortable,
            boolean unique,
            List<String> enumValues,
            Map<String, Object> validation,
            Map<String, Object> config
    ) {}

    public record RelationshipDefinitionDto(
            String name,
            String sourceObjectType,
            String targetObjectType,
            String relationshipType,
            boolean required,
            boolean cascadeDelete
    ) {}
}
