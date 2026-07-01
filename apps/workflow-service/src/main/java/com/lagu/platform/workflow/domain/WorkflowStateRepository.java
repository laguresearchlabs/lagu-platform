package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStateRepository extends JpaRepository<WorkflowState, UUID> {

    List<WorkflowState> findByWorkflowIdOrderByDisplayOrderAsc(UUID workflowId);

    Optional<WorkflowState> findByWorkflowIdAndName(UUID workflowId, String name);
}
