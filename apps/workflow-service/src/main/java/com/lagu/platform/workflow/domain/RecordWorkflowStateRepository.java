package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordWorkflowStateRepository extends JpaRepository<RecordWorkflowState, UUID> {

    Optional<RecordWorkflowState> findByRecordId(UUID recordId);

    /** Tenancy-scoped lookup — see StateMachineEngine for why the unscoped variant above must
     *  never be used to answer a request driven by caller-supplied identity. */
    Optional<RecordWorkflowState> findByRecordIdAndOrgId(UUID recordId, UUID orgId);

    List<RecordWorkflowState> findByOrgIdAndObjectType(UUID orgId, String objectType);
}
