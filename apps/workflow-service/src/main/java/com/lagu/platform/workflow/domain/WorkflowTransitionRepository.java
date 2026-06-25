package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {

    List<WorkflowTransition> findByWorkflowIdAndFromState(UUID workflowId, String fromState);

    Optional<WorkflowTransition> findByWorkflowIdAndFromStateAndTriggerName(
            UUID workflowId, String fromState, String triggerName);
}
