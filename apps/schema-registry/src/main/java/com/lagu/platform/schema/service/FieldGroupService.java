package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.schema.domain.*;
import com.lagu.platform.schema.dto.FieldGroupRequest;
import com.lagu.platform.schema.dto.FieldGroupResponse;
import com.lagu.platform.schema.dto.FieldResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class FieldGroupService {

    private final FieldGroupRepository fieldGroupRepo;
    private final FieldDefinitionRepository fieldRepo;
    private final FieldService fieldService;
    private final ListingTypeService listingTypeService;

    public List<FieldGroupResponse> listPlatformLevel() {
        return fieldGroupRepo.findByTenantIdIsNullAndActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    public FieldGroupResponse getById(UUID id) {
        return toResponse(findById(id));
    }

    @Transactional
    public FieldGroupResponse create(FieldGroupRequest req) {
        FieldGroup group = new FieldGroup();
        group.setName(req.name());
        group.setLabel(req.label());
        group.setDescription(req.description());

        if (req.entries() != null) {
            List<FieldGroupEntry> entries = buildEntries(group, req.entries());
            group.setEntries(entries);
        }

        FieldGroup saved = fieldGroupRepo.save(group);
        log.info("Created FieldGroup: {}", saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public FieldGroupResponse update(UUID id, FieldGroupRequest req) {
        FieldGroup group = findById(id);
        group.setName(req.name());
        group.setLabel(req.label());
        group.setDescription(req.description());
        if (req.entries() != null) {
            reconcileEntries(group, req.entries());
        } else {
            group.getEntries().clear();
        }
        FieldGroupResponse response = toResponse(fieldGroupRepo.save(group));
        listingTypeService.evictSchemaCacheForFieldGroup(id);
        return response;
    }

    /**
     * Updates the entries collection in place instead of clear()+addAll(): entries is a
     * @OneToMany(orphanRemoval=true) keyed by the composite (fieldGroupId, fieldId). Clearing and
     * re-adding a new FieldGroupEntry instance with the same composite key in the same flush
     * makes Hibernate schedule both a delete (orphan removal) and a merge (cascade save) for the
     * same identity, which throws ObjectDeletedException("deleted object would be re-saved by
     * cascade"). Reconciling by field name — update existing rows in place, remove only what's
     * genuinely dropped, add only what's genuinely new — avoids that collision entirely.
     */
    private void reconcileEntries(FieldGroup group, List<FieldGroupRequest.FieldGroupEntryRequest> entryReqs) {
        Map<String, FieldGroupEntry> existingByFieldName = group.getEntries().stream()
                .collect(Collectors.toMap(e -> e.getField().getName(), e -> e));
        Set<String> requestedFieldNames = entryReqs.stream()
                .map(FieldGroupRequest.FieldGroupEntryRequest::fieldName)
                .collect(Collectors.toSet());

        group.getEntries().removeIf(e -> !requestedFieldNames.contains(e.getField().getName()));

        for (FieldGroupRequest.FieldGroupEntryRequest er : entryReqs) {
            FieldGroupEntry existing = existingByFieldName.get(er.fieldName());
            if (existing != null) {
                existing.setDisplayOrder(er.displayOrder());
                existing.setRequired(er.required());
                existing.setVisibleWhen(ListingTypeService.validatedRule(er.visibleWhen(), "field " + er.fieldName()));
            } else {
                FieldDefinition field = fieldRepo.findByNameAndTenantIdIsNull(er.fieldName())
                        .orElseThrow(() -> new ResourceNotFoundException("FieldDefinition", er.fieldName()));
                FieldGroupEntry entry = new FieldGroupEntry();
                entry.setFieldGroup(group);
                entry.setField(field);
                entry.setDisplayOrder(er.displayOrder());
                entry.setRequired(er.required());
                entry.setVisibleWhen(ListingTypeService.validatedRule(er.visibleWhen(), "field " + er.fieldName()));
                group.getEntries().add(entry);
            }
        }
    }

    @Transactional
    public void deactivate(UUID id) {
        FieldGroup group = findById(id);
        group.setActive(false);
        fieldGroupRepo.save(group);
        listingTypeService.evictSchemaCacheForFieldGroup(id);
    }

    private FieldGroup findById(UUID id) {
        return fieldGroupRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FieldGroup", id.toString()));
    }

    private List<FieldGroupEntry> buildEntries(FieldGroup group,
                                                List<FieldGroupRequest.FieldGroupEntryRequest> entryReqs) {
        List<FieldGroupEntry> entries = new ArrayList<>();
        for (FieldGroupRequest.FieldGroupEntryRequest er : entryReqs) {
            FieldDefinition field = fieldRepo.findByNameAndTenantIdIsNull(er.fieldName())
                    .orElseThrow(() -> new ResourceNotFoundException("FieldDefinition", er.fieldName()));
            FieldGroupEntry entry = new FieldGroupEntry();
            entry.setFieldGroup(group);
            entry.setField(field);
            entry.setDisplayOrder(er.displayOrder());
            entry.setRequired(er.required());
            entry.setVisibleWhen(ListingTypeService.validatedRule(er.visibleWhen(), "field " + er.fieldName()));
            entries.add(entry);
        }
        return entries;
    }

    public FieldGroupResponse toResponse(FieldGroup fg) {
        List<FieldGroupEntry> ordered = fg.getEntries().stream()
                .sorted(java.util.Comparator.comparingInt(FieldGroupEntry::getDisplayOrder))
                .toList();
        List<FieldResponse> fields = ordered.stream()
                .map(e -> fieldService.toResponse(e.getField()))
                .toList();
        return new FieldGroupResponse(fg.getId(), fg.getName(), fg.getLabel(), fg.getDescription(),
                fields, ListingTypeService.toEntryResponses(ordered));
    }
}
