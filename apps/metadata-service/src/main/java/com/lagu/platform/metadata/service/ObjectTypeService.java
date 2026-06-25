package com.lagu.platform.metadata.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.metadata.domain.*;
import com.lagu.platform.metadata.dto.*;
import com.lagu.platform.metadata.event.MetadataEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ObjectTypeService {

    public static final String CACHE_SCHEMA = "object-type-schema";

    private final ObjectTypeDefinitionRepository repository;
    private final EntityDefinitionRepository entityRepository;
    private final MetadataEventPublisher eventPublisher;

    public List<ObjectTypeResponse> listForOrg(UUID orgId) {
        return repository.findAllActiveForOrg(orgId).stream()
                .map(this::toResponse)
                .toList();
    }

    public ObjectTypeResponse getById(UUID id) {
        return toResponse(findById(id));
    }

    @Transactional
    public ObjectTypeResponse create(ObjectTypeRequest req, UUID orgId) {
        ObjectTypeDefinition def = new ObjectTypeDefinition();
        def.setOrgId(orgId);
        applyRequest(def, req);
        ObjectTypeDefinition saved = repository.save(def);
        eventPublisher.publishObjectTypeCreated(saved);
        return toResponse(saved);
    }

    @Transactional
    @CacheEvict(value = CACHE_SCHEMA, key = "#req.name")
    public ObjectTypeResponse update(UUID id, ObjectTypeRequest req) {
        ObjectTypeDefinition def = findById(id);
        applyRequest(def, req);
        ObjectTypeDefinition saved = repository.save(def);
        eventPublisher.publishObjectTypeUpdated(saved);
        return toResponse(saved);
    }

    @Transactional
    public ObjectTypeResponse addSection(UUID objectTypeId, ObjectTypeSectionRequest req) {
        ObjectTypeDefinition objectType = findById(objectTypeId);
        EntityDefinition entity = entityRepository.findById(req.getEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("EntityDefinition", req.getEntityId().toString()));

        ObjectTypeSection section = new ObjectTypeSection();
        section.setObjectType(objectType);
        section.setEntity(entity);
        section.setLabel(req.getLabel());
        section.setDisplayOrder(req.getDisplayOrder());
        section.setCollapsible(req.isCollapsible());
        objectType.getSections().add(section);

        ObjectTypeDefinition saved = repository.save(objectType);
        evictSchemaCache(objectType.getName());
        return toResponse(saved);
    }

    @Transactional
    public void removeSection(UUID objectTypeId, UUID sectionId) {
        ObjectTypeDefinition objectType = findById(objectTypeId);
        objectType.getSections().removeIf(s -> s.getId().equals(sectionId));
        repository.save(objectType);
        evictSchemaCache(objectType.getName());
    }

    @Cacheable(value = CACHE_SCHEMA, key = "#objectTypeName")
    public ObjectTypeSchema getSchema(String objectTypeName) {
        return getSchema(objectTypeName, null);
    }

    public ObjectTypeSchema getSchema(String objectTypeName, UUID orgId) {
        List<ObjectTypeDefinition> candidates = orgId != null
                ? repository.findByNameForOrg(objectTypeName, orgId)
                : repository.findByNameForOrg(objectTypeName, UUID.fromString("00000000-0000-0000-0000-000000000000"));

        ObjectTypeDefinition def = candidates.stream().findFirst()
                .orElseGet(() -> repository.findByNameAndOrgIdIsNull(objectTypeName)
                        .orElseThrow(() -> new ResourceNotFoundException("ObjectTypeDefinition", objectTypeName)));

        List<ObjectTypeSchema.FieldSchema> fields = new ArrayList<>();
        for (ObjectTypeSection section : def.getSections()) {
            for (EntityAttribute ea : section.getEntity().getAttributes()) {
                AttributeDefinition attr = ea.getAttribute();
                fields.add(ObjectTypeSchema.FieldSchema.builder()
                        .name(attr.getName())
                        .label(attr.getLabel())
                        .type(attr.getAttributeType())
                        .required(attr.isRequired() || ea.isRequired())
                        .searchable(attr.isSearchable())
                        .filterable(attr.isFilterable())
                        .sortable(attr.isSortable())
                        .facetable(attr.isFacetable())
                        .unique(attr.isUnique())
                        .enumValues(attr.getEnumValues())
                        .validation(attr.getValidationRules())
                        .config(attr.getConfig())
                        .build());
            }
        }

        return ObjectTypeSchema.builder()
                .objectType(def.getName())
                .fields(fields)
                .build();
    }

    @Transactional
    public void deactivate(UUID id) {
        ObjectTypeDefinition def = findById(id);
        def.setActive(false);
        repository.save(def);
        evictSchemaCache(def.getName());
    }

    private ObjectTypeDefinition findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ObjectTypeDefinition", id.toString()));
    }

    private void applyRequest(ObjectTypeDefinition def, ObjectTypeRequest req) {
        def.setName(req.getName().toUpperCase());
        def.setLabel(req.getLabel());
        def.setDescription(req.getDescription());
        def.setIcon(req.getIcon());
        def.setColor(req.getColor());
        def.setPublishable(req.isPublishable());
        def.setConfig(req.getConfig());
    }

    @CacheEvict(value = CACHE_SCHEMA, key = "#objectTypeName")
    public void evictSchemaCache(String objectTypeName) {
        // Spring AOP handles the cache eviction
    }

    public ObjectTypeResponse toResponse(ObjectTypeDefinition def) {
        List<ObjectTypeResponse.SectionResponse> sections = def.getSections().stream()
                .map(s -> ObjectTypeResponse.SectionResponse.builder()
                        .sectionId(s.getId())
                        .entityId(s.getEntity().getId())
                        .entityName(s.getEntity().getName())
                        .label(s.getLabel() != null ? s.getLabel() : s.getEntity().getLabel())
                        .displayOrder(s.getDisplayOrder())
                        .collapsible(s.isCollapsible())
                        .build())
                .toList();

        return ObjectTypeResponse.builder()
                .id(def.getId())
                .orgId(def.getOrgId())
                .name(def.getName())
                .label(def.getLabel())
                .description(def.getDescription())
                .icon(def.getIcon())
                .color(def.getColor())
                .publishable(def.isPublishable())
                .active(def.isActive())
                .config(def.getConfig())
                .sections(sections)
                .createdAt(def.getCreatedAt())
                .updatedAt(def.getUpdatedAt())
                .build();
    }
}
