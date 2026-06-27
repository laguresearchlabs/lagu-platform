package com.lagu.platform.metadata.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.metadata.domain.RelationshipDefinition;
import com.lagu.platform.metadata.domain.RelationshipDefinitionRepository;
import com.lagu.platform.metadata.dto.RelationshipDefinitionRequest;
import com.lagu.platform.metadata.dto.RelationshipDefinitionResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationshipDefinitionService {

    private final RelationshipDefinitionRepository relDefRepo;

    public List<RelationshipDefinitionResponse> list() {
        UUID orgId = orgId();
        return (orgId != null ? relDefRepo.findAllForOrg(orgId) : relDefRepo.findAllPlatformLevel())
                .stream().map(this::toResponse).toList();
    }

    public RelationshipDefinitionResponse get(UUID id) {
        return toResponse(findById(id));
    }

    public RelationshipDefinitionResponse getByName(String name) {
        UUID orgId = orgId();
        RelationshipDefinition def = (orgId != null
                ? relDefRepo.findByNameAndOrgId(name.toUpperCase(), orgId)
                        .or(() -> relDefRepo.findByNameAndOrgIdIsNull(name.toUpperCase()))
                : relDefRepo.findByNameAndOrgIdIsNull(name.toUpperCase()))
                .orElseThrow(() -> new ResourceNotFoundException("RelationshipDefinition", name));
        return toResponse(def);
    }

    @Transactional
    public RelationshipDefinitionResponse create(RelationshipDefinitionRequest req) {
        UUID orgId = orgId();
        String name = req.getName().toUpperCase();

        if (orgId != null && relDefRepo.findByNameAndOrgId(name, orgId).isPresent()) {
            throw new ValidationException("Relationship definition '" + name + "' already exists");
        }
        if (orgId == null && relDefRepo.findByNameAndOrgIdIsNull(name).isPresent()) {
            throw new ValidationException("Relationship definition '" + name + "' already exists");
        }

        RelationshipDefinition def = new RelationshipDefinition();
        def.setOrgId(orgId);
        def.setName(name);
        def.setLabel(req.getLabel());
        def.setSourceObjectType(req.getSourceObjectType().toUpperCase());
        def.setTargetObjectType(req.getTargetObjectType().toUpperCase());
        def.setRelationshipType(req.getRelationshipType().toUpperCase());
        def.setRequired(req.isRequired());
        def.setCascadeDelete(req.isCascadeDelete());
        return toResponse(relDefRepo.save(def));
    }

    @Transactional
    public RelationshipDefinitionResponse update(UUID id, RelationshipDefinitionRequest req) {
        RelationshipDefinition def = findById(id);
        def.setLabel(req.getLabel());
        def.setRelationshipType(req.getRelationshipType().toUpperCase());
        def.setRequired(req.isRequired());
        def.setCascadeDelete(req.isCascadeDelete());
        return toResponse(relDefRepo.save(def));
    }

    @Transactional
    public void delete(UUID id) {
        RelationshipDefinition def = findById(id);
        def.setActive(false);
        relDefRepo.save(def);
    }

    private RelationshipDefinition findById(UUID id) {
        return relDefRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RelationshipDefinition", id.toString()));
    }

    private UUID orgId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null ? ctx.getOrgId() : null;
    }

    private RelationshipDefinitionResponse toResponse(RelationshipDefinition d) {
        return RelationshipDefinitionResponse.builder()
                .id(d.getId()).orgId(d.getOrgId()).name(d.getName()).label(d.getLabel())
                .sourceObjectType(d.getSourceObjectType()).targetObjectType(d.getTargetObjectType())
                .relationshipType(d.getRelationshipType()).required(d.isRequired())
                .cascadeDelete(d.isCascadeDelete()).active(d.isActive())
                .createdAt(d.getCreatedAt()).updatedAt(d.getUpdatedAt())
                .build();
    }
}
