package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {

    List<WorkflowTransition> findByWorkflowIdAndFromState(UUID workflowId, String fromState);

    /**
     * Trigger lookup is case-insensitive: the seeder stores lowercase trigger names
     * ("submit") while API callers may send any case — an exact match here would make
     * every seeded transition unreachable.
     */
    Optional<WorkflowTransition> findByWorkflowIdAndFromStateAndTriggerNameIgnoreCase(
            UUID workflowId, String fromState, String triggerName);
}
