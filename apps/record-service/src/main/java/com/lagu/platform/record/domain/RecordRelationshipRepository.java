package com.lagu.platform.record.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordRelationshipRepository extends JpaRepository<RecordRelationship, UUID> {

    List<RecordRelationship> findByOrgIdAndSourceRecordIdAndRelationshipName(
            UUID orgId, UUID sourceRecordId, String relationshipName);

    List<RecordRelationship> findByOrgIdAndSourceRecordId(UUID orgId, UUID sourceRecordId);

    Optional<RecordRelationship> findByRelationshipNameAndSourceRecordIdAndTargetRecordId(
            String relationshipName, UUID sourceRecordId, UUID targetRecordId);

    void deleteByRelationshipNameAndSourceRecordIdAndTargetRecordId(
            String relationshipName, UUID sourceRecordId, UUID targetRecordId);
}
