package com.lagu.platform.metadata.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.metadata.domain.*;
import com.lagu.platform.metadata.dto.EntityAttributeRequest;
import com.lagu.platform.metadata.dto.EntityRequest;
import com.lagu.platform.metadata.dto.EntityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntityService {

    private final EntityDefinitionRepository entityRepository;
    private final AttributeDefinitionRepository attributeRepository;

    public List<EntityResponse> listForOrg(UUID orgId) {
        return entityRepository.findAllActiveForOrg(orgId).stream()
                .map(this::toResponse)
                .toList();
    }

    public EntityResponse getById(UUID id) {
        return toResponse(findById(id));
    }

    @Transactional
    public EntityResponse create(EntityRequest req, UUID orgId) {
        EntityDefinition def = new EntityDefinition();
        def.setOrgId(orgId);
        def.setName(req.getName());
        def.setLabel(req.getLabel());
        def.setDescription(req.getDescription());
        return toResponse(entityRepository.save(def));
    }

    @Transactional
    public EntityResponse update(UUID id, EntityRequest req) {
        EntityDefinition def = findById(id);
        def.setName(req.getName());
        def.setLabel(req.getLabel());
        def.setDescription(req.getDescription());
        return toResponse(entityRepository.save(def));
    }

    @Transactional
    public EntityResponse addAttribute(UUID entityId, EntityAttributeRequest req) {
        EntityDefinition entity = findById(entityId);
        AttributeDefinition attribute = attributeRepository.findById(req.getAttributeId())
                .orElseThrow(() -> new ResourceNotFoundException("AttributeDefinition", req.getAttributeId().toString()));

        EntityAttribute ea = new EntityAttribute();
        ea.getId().setEntityId(entityId);
        ea.getId().setAttributeId(req.getAttributeId());
        ea.setEntity(entity);
        ea.setAttribute(attribute);
        ea.setDisplayOrder(req.getDisplayOrder());
        ea.setRequired(req.isRequired());
        entity.getAttributes().add(ea);

        return toResponse(entityRepository.save(entity));
    }

    @Transactional
    public EntityResponse removeAttribute(UUID entityId, UUID attributeId) {
        EntityDefinition entity = findById(entityId);
        entity.getAttributes().removeIf(ea -> ea.getAttribute().getId().equals(attributeId));
        return toResponse(entityRepository.save(entity));
    }

    @Transactional
    public void deactivate(UUID id) {
        EntityDefinition def = findById(id);
        def.setActive(false);
        entityRepository.save(def);
    }

    public EntityDefinition findById(UUID id) {
        return entityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EntityDefinition", id.toString()));
    }

    public EntityResponse toResponse(EntityDefinition def) {
        List<EntityResponse.EntityAttributeResponse> attrs = def.getAttributes().stream()
                .map(ea -> EntityResponse.EntityAttributeResponse.builder()
                        .attributeId(ea.getAttribute().getId())
                        .attributeName(ea.getAttribute().getName())
                        .attributeLabel(ea.getAttribute().getLabel())
                        .displayOrder(ea.getDisplayOrder())
                        .required(ea.isRequired())
                        .build())
                .toList();

        return EntityResponse.builder()
                .id(def.getId())
                .orgId(def.getOrgId())
                .name(def.getName())
                .label(def.getLabel())
                .description(def.getDescription())
                .active(def.isActive())
                .attributes(attrs)
                .createdAt(def.getCreatedAt())
                .updatedAt(def.getUpdatedAt())
                .build();
    }
}
