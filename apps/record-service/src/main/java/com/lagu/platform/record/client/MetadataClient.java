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

        // Sections are flattened away here, so a section's visibility rule must be folded into
        // each of its fields — a field is in play only when its section's rule AND its own allow
        // it. That keeps RecordValidator free of any notion of sections.
        List<FieldSchemaDto> fields = response.getData().sections().stream()
                .flatMap(s -> s.fields().stream()
                        .map(f -> new FieldSchemaDto(
                                f.key(), f.label(), f.fieldType(), f.required(),
                                f.searchable(), f.filterable(),
                                false,  // sortable — not exposed by schema-registry's schema endpoint; unused by current callers
                                false,  // unique — same
                                f.enumValues(), f.validationRules(), null, f.itemSchema(),
                                combineRules(s.visibleWhen(), f.visibleWhen()))))
                .toList();
        return new ObjectTypeSchemaDto(
                response.getData().listingType(), response.getData().version(), fields);
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

    /**
     * Combines a section's rule with a field's own into the field's effective rule. Either may be
     * absent, in which case the other stands alone; both absent means always visible.
     */
    private static Map<String, Object> combineRules(Map<String, Object> section, Map<String, Object> field) {
        boolean hasSection = section != null && !section.isEmpty();
        boolean hasField = field != null && !field.isEmpty();
        if (!hasSection) return hasField ? field : null;
        if (!hasField) return section;
        return Map.of("all", List.of(section, field));
    }

    public record ObjectTypeSchemaDto(String objectType, int version, List<FieldSchemaDto> fields) {

        /** For callers that do not care which schema version produced this — notably tests. */
        public ObjectTypeSchemaDto(String objectType, List<FieldSchemaDto> fields) {
            this(objectType, 0, fields);
        }
    }

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
            Map<String, Object> config,
            // For ARRAY_OF_OBJECTS fields: each entry describes one sub-field of an item using
            // the keys "name" (String), "type" (FieldType name), "required" (boolean, optional).
            List<Map<String, Object>> itemSchema,
            /** Effective visibility rule: this field's own ANDed with its section's. Null = always. */
            Map<String, Object> visibleWhen
    ) {
        /** Unconditionally-visible field — the common case, and what tests use unless the test
         *  is specifically about conditional visibility. */
        public FieldSchemaDto(String name, String label, String type, boolean required,
                              boolean searchable, boolean filterable, boolean sortable, boolean unique,
                              List<String> enumValues, Map<String, Object> validation,
                              Map<String, Object> config, List<Map<String, Object>> itemSchema) {
            this(name, label, type, required, searchable, filterable, sortable, unique,
                    enumValues, validation, config, itemSchema, null);
        }
    }

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

    private record SectionSchemaDto(String sectionKey, String label, int displayOrder,
                                    List<RawFieldSchema> fields, Map<String, Object> visibleWhen) {}

    private record RawFieldSchema(
            String key, String label, String fieldType, boolean required, boolean promoted,
            boolean searchable, boolean filterable, boolean facetable, boolean rangeFilterable,
            boolean arrayManageable, List<String> enumValues,
            List<Map<String, Object>> itemSchema, Map<String, Object> validationRules,
            Map<String, Object> visibleWhen
    ) {}

    private record RawRelationshipDefinition(
            String name, String sourceListingType, String targetListingType,
            String relationshipType, boolean required, boolean cascadeDelete
    ) {}
}
