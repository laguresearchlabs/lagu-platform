package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.schema.domain.FieldDefinition;
import com.lagu.platform.schema.domain.FieldDefinitionRepository;
import com.lagu.platform.schema.dto.FieldRequest;
import com.lagu.platform.schema.dto.FieldResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class FieldService {

    private final FieldDefinitionRepository repository;

    public List<FieldResponse> listPlatformLevel() {
        return repository.findByOrgIdIsNullAndActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    public FieldResponse getById(UUID id) {
        return toResponse(findById(id));
    }

    @Transactional
    public FieldResponse create(FieldRequest req) {
        FieldDefinition def = new FieldDefinition();
        applyRequest(def, req);
        FieldDefinition saved = repository.save(def);
        log.info("Created FieldDefinition: {}", saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public FieldResponse update(UUID id, FieldRequest req) {
        FieldDefinition def = findById(id);
        applyRequest(def, req);
        return toResponse(repository.save(def));
    }

    @Transactional
    public void deactivate(UUID id) {
        FieldDefinition def = findById(id);
        def.setActive(false);
        repository.save(def);
    }

    private FieldDefinition findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FieldDefinition", id.toString()));
    }

    private void applyRequest(FieldDefinition def, FieldRequest req) {
        def.setName(req.name());
        def.setLabel(req.label());
        def.setDescription(req.description());
        def.setFieldType(req.fieldType());
        def.setEnumValues(req.enumValues());
        def.setItemSchema(req.itemSchema());
        def.setReferenceType(req.referenceType());
        def.setRequired(req.required());
        def.setUnique(req.unique());
        def.setValidationRules(req.validationRules());
        def.setDefaultValue(req.defaultValue());
        def.setSearchable(req.searchable());
        def.setFilterable(req.filterable());
        def.setSortable(req.sortable());
        def.setFacetable(req.facetable());
        def.setPromoted(req.promoted());
        def.setRangeFilterable(req.rangeFilterable());
        def.setArrayManageable(req.arrayManageable());
    }

    public FieldResponse toResponse(FieldDefinition f) {
        return new FieldResponse(
                f.getId(), f.getOrgId(), f.getName(), f.getLabel(), f.getDescription(),
                f.getFieldType(), f.getEnumValues(), f.getItemSchema(), f.getReferenceType(),
                f.isRequired(), f.isUnique(), f.getValidationRules(), f.getDefaultValue(),
                f.isSearchable(), f.isFilterable(), f.isSortable(), f.isFacetable(),
                f.isPromoted(), f.isRangeFilterable(), f.isArrayManageable(),
                f.isActive(), f.getCreatedAt(), f.getUpdatedAt()
        );
    }
}
