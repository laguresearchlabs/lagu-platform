package com.lagu.platform.record.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordRelationshipRepository extends JpaRepository<RecordRelationship, UUID> {

    List<RecordRelationship> findByTenantIdAndSourceRecordIdAndRelationshipName(
            UUID tenantId, UUID sourceRecordId, String relationshipName);

    List<RecordRelationship> findByTenantIdAndSourceRecordId(UUID tenantId, UUID sourceRecordId);

    Optional<RecordRelationship> findByRelationshipNameAndSourceRecordIdAndTargetRecordId(
            String relationshipName, UUID sourceRecordId, UUID targetRecordId);

    void deleteByRelationshipNameAndSourceRecordIdAndTargetRecordId(
            String relationshipName, UUID sourceRecordId, UUID targetRecordId);
}
