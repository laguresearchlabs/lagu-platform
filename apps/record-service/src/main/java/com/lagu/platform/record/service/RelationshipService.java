package com.lagu.platform.record.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordRelationship;
import com.lagu.platform.record.domain.RecordRelationshipRepository;
import com.lagu.platform.record.domain.RecordRepository;
import com.lagu.platform.record.dto.CreateRelationshipRequest;
import com.lagu.platform.record.dto.RelationshipResponse;
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
public class RelationshipService {

    private final RecordRelationshipRepository relRepo;
    private final RecordRepository recordRepo;
    private final MetadataClient metadataClient;

    public List<RelationshipResponse> list(UUID sourceId, String relationshipName) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID tenantId = ctx != null ? ctx.getTenantId() : null;

        List<RecordRelationship> rels = relationshipName != null
                ? relRepo.findByTenantIdAndSourceRecordIdAndRelationshipName(tenantId, sourceId, relationshipName.toUpperCase())
                : relRepo.findByTenantIdAndSourceRecordId(tenantId, sourceId);

        return rels.stream().map(this::toResponse).toList();
    }

    @Transactional
    public RelationshipResponse create(UUID sourceId, CreateRelationshipRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID tenantId = ctx != null ? ctx.getTenantId() : null;

        Record source = recordRepo.findByIdAndTenantId(sourceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Record", sourceId.toString()));

        // Target lookup is intentionally org-unscoped: relationships routinely cross org
        // boundaries (e.g. an event record linking to a vendor's VENUE/PHOTOGRAPHER record in a
        // different org — every event and every vendor mints its own throwaway org). Visibility
        // into the target is gated below instead of by org membership.
        Record target = recordRepo.findByIdExcludingDeleted(req.getTargetRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("Record", req.getTargetRecordId().toString()));

        if (source.getId().equals(target.getId())) {
            throw new ValidationException("Source and target record cannot be the same");
        }

        String relName = req.getRelationshipName().toUpperCase();

        // Validate against definition when available
        MetadataClient.RelationshipDefinitionDto relDef = metadataClient.getRelationshipDefinition(relName);
        boolean crossOrg = !target.getTenantId().equals(tenantId);
        if (crossOrg && relDef == null) {
            // Cross-org linking is only ever allowed for a known, schema-registry-declared
            // relationship — never a blind link to an arbitrary record in another org.
            throw new ValidationException("Relationship '" + relName + "' is not a recognized cross-org relationship type");
        }
        if (crossOrg && "DRAFT".equalsIgnoreCase(target.getStatus())) {
            // Don't let a record in one org discover/link to another org's private draft record.
            throw new ResourceNotFoundException("Record", req.getTargetRecordId().toString());
        }
        if (relDef != null) {
            if (!source.getObjectType().equalsIgnoreCase(relDef.sourceObjectType())) {
                throw new ValidationException("Source record type '" + source.getObjectType()
                        + "' does not match relationship definition source type '" + relDef.sourceObjectType() + "'");
            }
            if (!target.getObjectType().equalsIgnoreCase(relDef.targetObjectType())) {
                throw new ValidationException("Target record type '" + target.getObjectType()
                        + "' does not match relationship definition target type '" + relDef.targetObjectType() + "'");
            }
            if ("ONE_TO_ONE".equals(relDef.relationshipType())) {
                List<RecordRelationship> existing =
                        relRepo.findByTenantIdAndSourceRecordIdAndRelationshipName(tenantId, sourceId, relName);
                if (!existing.isEmpty()) {
                    throw new ValidationException("A ONE_TO_ONE relationship '" + relName + "' already exists for this record");
                }
            }
        }

        relRepo.findByRelationshipNameAndSourceRecordIdAndTargetRecordId(relName, sourceId, target.getId())
                .ifPresent(r -> { throw new ValidationException("Relationship already exists"); });

        RecordRelationship rel = new RecordRelationship();
        rel.setTenantId(tenantId);
        rel.setRelationshipName(relName);
        rel.setSourceRecordId(sourceId);
        rel.setTargetRecordId(target.getId());
        rel.setCreatedBy(ctx != null ? ctx.getUserId() : null);

        return toResponse(relRepo.save(rel));
    }

    @Transactional
    public void delete(UUID sourceId, String relationshipName, UUID targetId) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID tenantId = ctx != null ? ctx.getTenantId() : null;

        // Verify source belongs to this org
        recordRepo.findByIdAndTenantId(sourceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Record", sourceId.toString()));

        String relName = relationshipName.toUpperCase();
        relRepo.findByRelationshipNameAndSourceRecordIdAndTargetRecordId(relName, sourceId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship", relName));

        relRepo.deleteByRelationshipNameAndSourceRecordIdAndTargetRecordId(relName, sourceId, targetId);
    }

    private RelationshipResponse toResponse(RecordRelationship rel) {
        // Excludes soft-deleted targets — a relationship pointing at a since-deleted record must
        // stop surfacing that record's data, not keep echoing it back forever.
        Record target = recordRepo.findByIdExcludingDeleted(rel.getTargetRecordId()).orElse(null);
        return RelationshipResponse.builder()
                .id(rel.getId())
                .relationshipName(rel.getRelationshipName())
                .sourceRecordId(rel.getSourceRecordId())
                .targetRecordId(rel.getTargetRecordId())
                .targetObjectType(target != null ? target.getObjectType() : null)
                .targetStatus(target != null ? target.getStatus() : null)
                .targetData(target != null ? target.getData() : null)
                .createdAt(rel.getCreatedAt())
                .build();
    }
}
