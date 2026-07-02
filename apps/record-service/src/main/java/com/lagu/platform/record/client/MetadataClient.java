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

/**
 * Fetches schema/relationship definitions from schema-registry (metadata-service's schema
 * responsibilities were absorbed into schema-registry — see todo/13-no-code-vendor-platform-adr.md).
 * Public DTOs here (ObjectTypeSchemaDto, FieldSchemaDto, RelationshipDefinitionDto) are unchanged
 * so RecordValidator/RelationshipService/RecordFileController didn't need to change at all — this
 * class alone adapts schema-registry's section-nested, differently-named response shape into them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataClient {

    public static final String SCHEMA_CACHE = "metadata-schema";

    private final RestClient schemaRegistryRestClient;
    private final ObjectMapper objectMapper;

    @Cacheable(value = SCHEMA_CACHE, key = "#objectTypeName")
    public ObjectTypeSchemaDto getSchema(String objectTypeName) {
        log.debug("Fetching schema for objectType={}", objectTypeName);
        ApiResponse<ListingTypeSchemaDto> response = schemaRegistryRestClient.get()
                .uri("/api/v1/listing-types/{name}/schema", objectTypeName)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new IllegalStateException("Schema not found for objectType: " + objectTypeName);
        }

        List<FieldSchemaDto> fields = response.getData().sections().stream()
                .flatMap(s -> s.fields().stream())
                .map(f -> new FieldSchemaDto(
                        f.key(), f.label(), f.fieldType(), f.required(),
                        f.searchable(), f.filterable(),
                        false,  // sortable — not exposed by schema-registry's schema endpoint; unused by current callers
                        false,  // unique — same
                        f.enumValues(), f.validationRules(), null))
                .toList();
        return new ObjectTypeSchemaDto(response.getData().listingType(), fields);
    }

    public RelationshipDefinitionDto getRelationshipDefinition(String name) {
        log.debug("Fetching relationship definition name={}", name);
        try {
            ApiResponse<RawRelationshipDefinition> response = schemaRegistryRestClient.get()
                    .uri("/api/v1/relationship-definitions/by-name/{name}", name)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null || !response.isSuccess() || response.getData() == null) return null;
            RawRelationshipDefinition raw = response.getData();
            return new RelationshipDefinitionDto(raw.name(), raw.sourceListingType(), raw.targetListingType(),
                    raw.relationshipType(), raw.required(), raw.cascadeDelete());
        } catch (Exception e) {
            log.warn("Relationship definition '{}' not found in schema-registry: {}", name, e.getMessage());
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

    // ── schema-registry's raw wire shapes (private to this adapter) ────────────

    private record ListingTypeSchemaDto(String listingType, int version, List<SectionSchemaDto> sections) {}

    private record SectionSchemaDto(String sectionKey, String label, int displayOrder, List<RawFieldSchema> fields) {}

    private record RawFieldSchema(
            String key, String label, String fieldType, boolean required, boolean promoted,
            boolean searchable, boolean filterable, boolean facetable, boolean rangeFilterable,
            boolean arrayManageable, List<String> enumValues,
            List<Map<String, Object>> itemSchema, Map<String, Object> validationRules
    ) {}

    private record RawRelationshipDefinition(
            String name, String sourceListingType, String targetListingType,
            String relationshipType, boolean required, boolean cascadeDelete
    ) {}
}
