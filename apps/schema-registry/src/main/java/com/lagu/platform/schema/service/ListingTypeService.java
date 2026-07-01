package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.schema.domain.*;
import com.lagu.platform.schema.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ListingTypeService {

    public static final String CACHE_SCHEMA = "schema-registry:schema";

    private final ListingTypeDefinitionRepository listingTypeRepo;
    private final FieldGroupRepository fieldGroupRepo;

    public List<ListingTypeResponse> list() {
        return listingTypeRepo.findByOrgIdIsNullAndActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    public ListingTypeResponse getByName(String name) {
        ListingTypeDefinition def = listingTypeRepo.findByNameAndOrgIdIsNull(name)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", name));
        return toResponse(def);
    }

    @Cacheable(value = CACHE_SCHEMA, key = "#name")
    public ListingTypeSchemaDto getSchema(String name) {
        ListingTypeDefinition def = listingTypeRepo.findByNameWithSectionsAndOrgIdIsNull(name)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", name));
        return toSchemaDto(def);
    }

    @Transactional
    public ListingTypeResponse create(ListingTypeRequest req) {
        ListingTypeDefinition def = new ListingTypeDefinition();
        def.setName(req.name().toUpperCase());
        def.setLabel(req.label());
        def.setDescription(req.description());
        def.setIcon(req.icon());
        def.setColor(req.color());
        def.setPublishable(req.publishable());
        def.setConsumerSearchable(req.consumerSearchable());

        if (req.sections() != null) {
            List<ListingTypeSection> sections = new ArrayList<>();
            for (ListingTypeRequest.SectionRequest secReq : req.sections()) {
                FieldGroup fg = fieldGroupRepo.findByNameWithFieldsAndOrgIdIsNull(secReq.fieldGroupName())
                        .orElseThrow(() -> new ResourceNotFoundException("FieldGroup", secReq.fieldGroupName()));
                ListingTypeSection sec = new ListingTypeSection();
                sec.setListingType(def);
                sec.setFieldGroup(fg);
                sec.setLabel(secReq.label());
                sec.setSectionKey(secReq.sectionKey());
                sec.setDisplayOrder(secReq.displayOrder());
                sec.setCollapsible(secReq.collapsible());
                sections.add(sec);
            }
            def.setSections(sections);
        }

        ListingTypeDefinition saved = listingTypeRepo.save(def);
        log.info("Created ListingTypeDefinition: {}", saved.getName());
        return toResponse(saved);
    }

    @Transactional
    @CacheEvict(value = CACHE_SCHEMA, key = "#name")
    public ListingTypeResponse addSection(String name, ListingTypeRequest.SectionRequest secReq) {
        ListingTypeDefinition def = listingTypeRepo.findByNameAndOrgIdIsNull(name)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", name));

        FieldGroup fg = fieldGroupRepo.findByNameWithFieldsAndOrgIdIsNull(secReq.fieldGroupName())
                .orElseThrow(() -> new ResourceNotFoundException("FieldGroup", secReq.fieldGroupName()));

        ListingTypeSection sec = new ListingTypeSection();
        sec.setListingType(def);
        sec.setFieldGroup(fg);
        sec.setLabel(secReq.label());
        sec.setSectionKey(secReq.sectionKey());
        sec.setDisplayOrder(secReq.displayOrder());
        sec.setCollapsible(secReq.collapsible());
        def.getSections().add(sec);

        ListingTypeDefinition saved = listingTypeRepo.save(def);
        return toResponse(saved);
    }

    @Transactional
    public ListingTypeResponse update(UUID id, ListingTypeRequest req) {
        ListingTypeDefinition def = listingTypeRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", id.toString()));
        def.setLabel(req.label());
        def.setDescription(req.description());
        def.setIcon(req.icon());
        def.setColor(req.color());
        def.setPublishable(req.publishable());
        def.setConsumerSearchable(req.consumerSearchable());
        ListingTypeDefinition saved = listingTypeRepo.save(def);
        evictSchemaCache(saved.getName());
        return toResponse(saved);
    }

    @CacheEvict(value = CACHE_SCHEMA, key = "#listingTypeName")
    public void evictSchemaCache(String listingTypeName) {
        // Spring AOP handles the cache eviction
    }

    @Transactional
    public void deactivate(UUID id) {
        ListingTypeDefinition def = listingTypeRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", id.toString()));
        def.setActive(false);
        listingTypeRepo.save(def);
        log.info("Deactivated ListingTypeDefinition id={}", id);
    }

    // ── Mapping helpers ────────────────────────────────────────────────────────

    public ListingTypeSchemaDto toSchemaDto(ListingTypeDefinition def) {
        List<ListingTypeSchemaDto.SectionSchemaDto> sections = def.getSections().stream()
                .map(sec -> {
                    List<ListingTypeSchemaDto.FieldSchemaDto> fields = sec.getFieldGroup().getEntries().stream()
                            .sorted(java.util.Comparator.comparingInt(FieldGroupEntry::getDisplayOrder))
                            .map(entry -> {
                                FieldDefinition f = entry.getField();
                                return new ListingTypeSchemaDto.FieldSchemaDto(
                                        f.getName(),
                                        f.getLabel(),
                                        f.getFieldType(),
                                        f.isRequired() || entry.isRequired(),
                                        f.isPromoted(),
                                        f.isSearchable(),
                                        f.isFilterable(),
                                        f.isFacetable(),
                                        f.isRangeFilterable(),
                                        f.isArrayManageable(),
                                        f.getEnumValues(),
                                        f.getItemSchema(),
                                        f.getValidationRules()
                                );
                            })
                            .toList();
                    return new ListingTypeSchemaDto.SectionSchemaDto(
                            sec.getSectionKey(),
                            sec.getLabel() != null ? sec.getLabel() : sec.getFieldGroup().getLabel(),
                            sec.getDisplayOrder(),
                            fields
                    );
                })
                .toList();

        return new ListingTypeSchemaDto(def.getName(), def.getCurrentVersion(), sections);
    }

    public ListingTypeResponse toResponse(ListingTypeDefinition def) {
        List<ListingTypeResponse.SectionResponse> sections = def.getSections().stream()
                .map(sec -> new ListingTypeResponse.SectionResponse(
                        sec.getId(),
                        sec.getSectionKey(),
                        sec.getLabel() != null ? sec.getLabel() : sec.getFieldGroup().getLabel(),
                        sec.getDisplayOrder(),
                        sec.isCollapsible(),
                        toFieldGroupResponse(sec.getFieldGroup())
                ))
                .toList();

        return new ListingTypeResponse(
                def.getId(),
                def.getName(),
                def.getLabel(),
                def.getDescription(),
                def.getIcon(),
                def.getColor(),
                def.isPublishable(),
                def.isConsumerSearchable(),
                def.isActive(),
                def.getCurrentVersion(),
                sections
        );
    }

    private FieldGroupResponse toFieldGroupResponse(FieldGroup fg) {
        List<FieldResponse> fields = fg.getEntries().stream()
                .sorted(java.util.Comparator.comparingInt(FieldGroupEntry::getDisplayOrder))
                .map(entry -> toFieldResponse(entry.getField()))
                .toList();
        return new FieldGroupResponse(fg.getId(), fg.getName(), fg.getLabel(), fg.getDescription(), fields);
    }

    private FieldResponse toFieldResponse(FieldDefinition f) {
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
