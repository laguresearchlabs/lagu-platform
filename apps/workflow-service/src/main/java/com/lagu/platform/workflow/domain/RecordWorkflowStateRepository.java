package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordWorkflowStateRepository extends JpaRepository<RecordWorkflowState, UUID> {

    Optional<RecordWorkflowState> findByRecordId(UUID recordId);

    List<RecordWorkflowState> findByOrgIdAndObjectType(UUID orgId, String objectType);
}
