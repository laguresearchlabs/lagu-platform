package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.common.visibility.VisibilityRules;
import com.lagu.platform.schema.domain.*;
import com.lagu.platform.schema.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ListingTypeService {

    public static final String CACHE_SCHEMA = "schema-registry:schema";

    private final ListingTypeDefinitionRepository listingTypeRepo;
    private final FieldGroupRepository fieldGroupRepo;
    private final CacheManager cacheManager;

    public List<ListingTypeResponse> list() {
        return listingTypeRepo.findByTenantIdIsNullAndActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    public ListingTypeResponse getByName(String name) {
        ListingTypeDefinition def = listingTypeRepo.findByNameAndTenantIdIsNull(name)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", name));
        return toResponse(def);
    }

    @Cacheable(value = CACHE_SCHEMA, key = "#name")
    public ListingTypeSchemaDto getSchema(String name) {
        ListingTypeDefinition def = listingTypeRepo.findByNameWithSectionsAndTenantIdIsNull(name)
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
        def.setKind(req.kind() != null ? req.kind() : ListingTypeKind.LISTING);
        def.setConfig(req.config());
        def.setPublishable(req.publishable());
        def.setConsumerSearchable(req.consumerSearchable());

        if (req.sections() != null) {
            List<ListingTypeSection> sections = new ArrayList<>();
            for (ListingTypeRequest.SectionRequest secReq : req.sections()) {
                FieldGroup fg = fieldGroupRepo.findByNameWithFieldsAndTenantIdIsNull(secReq.fieldGroupName())
                        .orElseThrow(() -> new ResourceNotFoundException("FieldGroup", secReq.fieldGroupName()));
                ListingTypeSection sec = new ListingTypeSection();
                sec.setListingType(def);
                sec.setFieldGroup(fg);
                sec.setLabel(secReq.label());
                sec.setSectionKey(secReq.sectionKey());
                sec.setDisplayOrder(secReq.displayOrder());
                sec.setCollapsible(secReq.collapsible());
                sec.setVisibleWhen(validatedRule(secReq.visibleWhen(), "section " + secReq.sectionKey()));
                sections.add(sec);
            }
            def.setSections(sections);
        }

        ListingTypeDefinition saved = listingTypeRepo.save(def);
        log.info("Created ListingTypeDefinition: {}", saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public ListingTypeResponse addSection(String name, ListingTypeRequest.SectionRequest secReq) {
        ListingTypeDefinition def = listingTypeRepo.findByNameAndTenantIdIsNull(name)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", name));

        FieldGroup fg = fieldGroupRepo.findByNameWithFieldsAndTenantIdIsNull(secReq.fieldGroupName())
                .orElseThrow(() -> new ResourceNotFoundException("FieldGroup", secReq.fieldGroupName()));

        ListingTypeSection sec = new ListingTypeSection();
        sec.setListingType(def);
        sec.setFieldGroup(fg);
        sec.setLabel(secReq.label());
        sec.setSectionKey(secReq.sectionKey());
        sec.setDisplayOrder(secReq.displayOrder());
        sec.setCollapsible(secReq.collapsible());
        sec.setVisibleWhen(validatedRule(secReq.visibleWhen(), "section " + secReq.sectionKey()));
        def.getSections().add(sec);

        ListingTypeDefinition saved = listingTypeRepo.save(def);
        evictSchemaCache(saved.getName());
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
        if (req.kind() != null) def.setKind(req.kind());
        // Null leaves existing presentation config alone, so a caller that does not manage it
        // cannot wipe it by omission.
        if (req.config() != null) def.setConfig(req.config());
        def.setPublishable(req.publishable());
        def.setConsumerSearchable(req.consumerSearchable());
        ListingTypeDefinition saved = listingTypeRepo.save(def);
        evictSchemaCache(saved.getName());
        return toResponse(saved);
    }

    /**
     * Explicit (non-annotation) eviction so it also works via self-invocation from other methods
     * in this class — {@code @CacheEvict} only fires on calls that go through the Spring proxy,
     * which self-invocation (a method calling another method on {@code this}) bypasses.
     */
    public void evictSchemaCache(String listingTypeName) {
        Cache cache = cacheManager.getCache(CACHE_SCHEMA);
        if (cache != null) {
            cache.evict(listingTypeName);
        }
    }

    /** Evicts every listing type whose schema embeds the given field group, e.g. after a FieldGroup edit. */
    public void evictSchemaCacheForFieldGroup(UUID fieldGroupId) {
        listingTypeRepo.findNamesByFieldGroupId(fieldGroupId).forEach(this::evictSchemaCache);
    }

    /** Evicts every listing type whose schema embeds the given field, e.g. after a Field edit. */
    public void evictSchemaCacheForField(UUID fieldId) {
        listingTypeRepo.findNamesByFieldId(fieldId).forEach(this::evictSchemaCache);
    }

    @Transactional
    public void deactivate(UUID id) {
        ListingTypeDefinition def = listingTypeRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", id.toString()));
        def.setActive(false);
        listingTypeRepo.save(def);
        log.info("Deactivated ListingTypeDefinition id={}", id);
    }

    /**
     * Rejects a malformed rule at the point of authoring rather than letting it reach the
     * database, where it would silently evaluate as "always visible" on every read.
     * Cross-field checks (does the referenced field exist? is there a cycle?) can only be done
     * against a whole listing type, so those live in {@link SchemaRuleValidator} at publish time.
     */
    static Map<String, Object> validatedRule(Map<String, Object> rule, String where) {
        if (rule == null || rule.isEmpty()) return null;
        try {
            VisibilityRules.parseStrict(rule);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid visibleWhen on " + where + ": " + e.getMessage());
        }
        return rule;
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
                                        f.getValidationRules(),
                                        entry.getVisibleWhen()
                                );
                            })
                            .toList();
                    return new ListingTypeSchemaDto.SectionSchemaDto(
                            sec.getSectionKey(),
                            sec.getLabel() != null ? sec.getLabel() : sec.getFieldGroup().getLabel(),
                            sec.getDisplayOrder(),
                            fields,
                            sec.getVisibleWhen()
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
                def.getKind(),
                def.getConfig(),
                def.isPublishable(),
                def.isConsumerSearchable(),
                def.isActive(),
                def.getCurrentVersion(),
                sections
        );
    }

    private FieldGroupResponse toFieldGroupResponse(FieldGroup fg) {
        List<FieldGroupEntry> ordered = fg.getEntries().stream()
                .sorted(java.util.Comparator.comparingInt(FieldGroupEntry::getDisplayOrder))
                .toList();
        List<FieldResponse> fields = ordered.stream()
                .map(entry -> toFieldResponse(entry.getField()))
                .toList();
        return new FieldGroupResponse(fg.getId(), fg.getName(), fg.getLabel(), fg.getDescription(),
                fields, toEntryResponses(ordered));
    }

    /** Per-placement data the global FieldResponse cannot carry — see FieldGroupResponse.entries. */
    static List<FieldGroupResponse.EntryResponse> toEntryResponses(List<FieldGroupEntry> ordered) {
        return ordered.stream()
                .map(e -> new FieldGroupResponse.EntryResponse(
                        e.getField().getName(), e.getDisplayOrder(), e.isRequired(), e.getVisibleWhen()))
                .toList();
    }

    private FieldResponse toFieldResponse(FieldDefinition f) {
        return new FieldResponse(
                f.getId(), f.getTenantId(), f.getName(), f.getLabel(), f.getDescription(),
                f.getFieldType(), f.getEnumValues(), f.getItemSchema(), f.getReferenceType(),
                f.isRequired(), f.isUnique(), f.getValidationRules(), f.getDefaultValue(),
                f.isSearchable(), f.isFilterable(), f.isSortable(), f.isFacetable(),
                f.isPromoted(), f.isRangeFilterable(), f.isArrayManageable(),
                f.isActive(), f.getCreatedAt(), f.getUpdatedAt()
        );
    }
}
