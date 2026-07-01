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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class FieldGroupService {

    private final FieldGroupRepository fieldGroupRepo;
    private final FieldDefinitionRepository fieldRepo;
    private final FieldService fieldService;

    public List<FieldGroupResponse> listPlatformLevel() {
        return fieldGroupRepo.findByOrgIdIsNullAndActiveTrue().stream()
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
        group.getEntries().clear();
        if (req.entries() != null) {
            group.getEntries().addAll(buildEntries(group, req.entries()));
        }
        return toResponse(fieldGroupRepo.save(group));
    }

    @Transactional
    public void deactivate(UUID id) {
        FieldGroup group = findById(id);
        group.setActive(false);
        fieldGroupRepo.save(group);
    }

    private FieldGroup findById(UUID id) {
        return fieldGroupRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FieldGroup", id.toString()));
    }

    private List<FieldGroupEntry> buildEntries(FieldGroup group,
                                                List<FieldGroupRequest.FieldGroupEntryRequest> entryReqs) {
        List<FieldGroupEntry> entries = new ArrayList<>();
        for (FieldGroupRequest.FieldGroupEntryRequest er : entryReqs) {
            FieldDefinition field = fieldRepo.findByNameAndOrgIdIsNull(er.fieldName())
                    .orElseThrow(() -> new ResourceNotFoundException("FieldDefinition", er.fieldName()));
            FieldGroupEntry entry = new FieldGroupEntry();
            entry.setFieldGroup(group);
            entry.setField(field);
            entry.setDisplayOrder(er.displayOrder());
            entry.setRequired(er.required());
            entries.add(entry);
        }
        return entries;
    }

    public FieldGroupResponse toResponse(FieldGroup fg) {
        List<FieldResponse> fields = fg.getEntries().stream()
                .sorted(java.util.Comparator.comparingInt(FieldGroupEntry::getDisplayOrder))
                .map(e -> fieldService.toResponse(e.getField()))
                .toList();
        return new FieldGroupResponse(fg.getId(), fg.getName(), fg.getLabel(), fg.getDescription(), fields);
    }
}
