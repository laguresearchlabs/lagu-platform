package com.lagu.platform.metadata.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.metadata.domain.AttributeDefinition;
import com.lagu.platform.metadata.domain.AttributeDefinitionRepository;
import com.lagu.platform.metadata.domain.AttributeType;
import com.lagu.platform.metadata.dto.AttributeRequest;
import com.lagu.platform.metadata.dto.AttributeResponse;
import com.lagu.platform.metadata.event.MetadataEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttributeService {

    private static final Set<AttributeType> ENUM_TYPES =
            EnumSet.of(AttributeType.ENUM, AttributeType.MULTI_SELECT);

    private final AttributeDefinitionRepository repository;
    private final MetadataEventPublisher eventPublisher;

    public List<AttributeResponse> listForOrg(UUID orgId) {
        return repository.findAllActiveForOrg(orgId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<AttributeResponse> listPlatformLevel() {
        return repository.findAllPlatformLevel().stream()
                .map(this::toResponse)
                .toList();
    }

    public AttributeResponse getById(UUID id) {
        return toResponse(findById(id));
    }

    @Transactional
    public AttributeResponse create(AttributeRequest req, UUID orgId) {
        validate(req);

        AttributeDefinition def = new AttributeDefinition();
        def.setOrgId(orgId);
        applyRequest(def, req);

        AttributeDefinition saved = repository.save(def);
        eventPublisher.publishAttributeCreated(saved);
        return toResponse(saved);
    }

    @Transactional
    public AttributeResponse update(UUID id, AttributeRequest req) {
        AttributeDefinition def = findById(id);
        validate(req);
        applyRequest(def, req);
        AttributeDefinition saved = repository.save(def);
        eventPublisher.publishAttributeUpdated(saved);
        return toResponse(saved);
    }

    @Transactional
    public void deactivate(UUID id) {
        AttributeDefinition def = findById(id);
        def.setActive(false);
        repository.save(def);
    }

    private AttributeDefinition findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AttributeDefinition", id.toString()));
    }

    private void validate(AttributeRequest req) {
        if (ENUM_TYPES.contains(req.getAttributeType())
                && (req.getEnumValues() == null || req.getEnumValues().isEmpty())) {
            throw new ValidationException("AttributeDefinition",
                    List.of("enumValues must not be empty for type " + req.getAttributeType()));
        }
        if (req.getAttributeType() == AttributeType.ENTITY_REFERENCE
                && (req.getConfig() == null || !req.getConfig().containsKey("targetObjectType"))) {
            throw new ValidationException("AttributeDefinition",
                    List.of("config.targetObjectType is required for ENTITY_REFERENCE type"));
        }
    }

    private void applyRequest(AttributeDefinition def, AttributeRequest req) {
        def.setName(req.getName());
        def.setLabel(req.getLabel());
        def.setDescription(req.getDescription());
        def.setAttributeType(req.getAttributeType());
        def.setRequired(req.isRequired());
        def.setSearchable(req.isSearchable());
        def.setFilterable(req.isFilterable());
        def.setSortable(req.isSortable());
        def.setFacetable(req.isFacetable());
        def.setUnique(req.isUnique());
        def.setDefaultValue(req.getDefaultValue());
        def.setValidationRules(req.getValidationRules());
        def.setEnumValues(req.getEnumValues());
        def.setConfig(req.getConfig());
    }

    public AttributeResponse toResponse(AttributeDefinition def) {
        return AttributeResponse.builder()
                .id(def.getId())
                .orgId(def.getOrgId())
                .name(def.getName())
                .label(def.getLabel())
                .description(def.getDescription())
                .attributeType(def.getAttributeType())
                .required(def.isRequired())
                .searchable(def.isSearchable())
                .filterable(def.isFilterable())
                .sortable(def.isSortable())
                .facetable(def.isFacetable())
                .unique(def.isUnique())
                .defaultValue(def.getDefaultValue())
                .validationRules(def.getValidationRules())
                .enumValues(def.getEnumValues())
                .config(def.getConfig())
                .active(def.isActive())
                .createdAt(def.getCreatedAt())
                .updatedAt(def.getUpdatedAt())
                .build();
    }
}
