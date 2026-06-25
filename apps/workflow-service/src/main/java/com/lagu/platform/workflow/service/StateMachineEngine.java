package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.events.WorkflowEvent;
import com.lagu.platform.workflow.domain.*;
import com.lagu.platform.workflow.dto.*;
import com.lagu.platform.workflow.event.WorkflowEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StateMachineEngine {

    private final WorkflowDefinitionRepository wfRepo;
    private final WorkflowTransitionRepository txRepo;
    private final RecordWorkflowStateRepository rwsRepo;
    private final TransitionHistoryRepository   histRepo;
    private final ApprovalEngine                approvalEngine;
    private final WorkflowEventPublisher        publisher;

    /**
     * Processes a STATUS_TRANSITION_REQUESTED event from record-service.
     * Either executes the transition immediately or starts an approval flow.
     */
    @Transactional
    public void processTransitionRequest(RecordEvent event) {
        UUID recordId  = event.getRecordId();
        UUID orgId     = event.getOrgId();
        String trigger = event.getTriggerName().toUpperCase();

        // Resolve workflow for this object type
        WorkflowDefinition wf = resolveWorkflow(event.getObjectType(), orgId);

        // Get or initialise runtime state
        RecordWorkflowState rws = rwsRepo.findByRecordId(recordId)
                .orElseGet(() -> initState(recordId, orgId, event.getObjectType(), wf));

        String currentState = rws.getCurrentState();

        // Find the transition
        WorkflowTransition transition = txRepo
                .findByWorkflowIdAndFromStateAndTriggerName(wf.getId(), currentState, trigger)
                .orElseThrow(() -> new ValidationException(
                        "No transition '" + trigger + "' from state '" + currentState + "'"));

        // Role check — null/empty means anyone can trigger
        UUID actorId = event.getChangedBy();
        Set<String> userRoles = resolveUserRoles(event);
        if (!isRoleAllowed(transition.getAllowedRoles(), userRoles)) {
            publisher.publishTransitionRejected(wf, rws, transition, actorId,
                    "User does not have permission to trigger " + trigger);
            return;
        }

        if (transition.isRequiresApproval() && transition.getApprovalDefinition() != null) {
            approvalEngine.startApproval(rws, transition, actorId);
        } else {
            executeTransition(rws, transition, actorId, event.getComment());
        }
    }

    void executeTransition(RecordWorkflowState rws, WorkflowTransition transition,
                           UUID actorId, String comment) {
        String fromState = rws.getCurrentState();
        rws.setCurrentState(transition.getToState());
        rws.setUpdatedBy(actorId);
        rwsRepo.save(rws);

        recordHistory(rws, transition, fromState, actorId, comment);
        publisher.publishTransitioned(rws.getWorkflow(), rws, transition, actorId, comment);
        log.info("Record {} transitioned {} → {} via {}", rws.getRecordId(), fromState,
                transition.getToState(), transition.getTriggerName());
    }

    public RecordWorkflowStatusResponse getStatus(UUID recordId, Set<String> userRoles) {
        RecordWorkflowState rws = rwsRepo.findByRecordId(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowState", recordId.toString()));

        WorkflowDefinition wf = rws.getWorkflow();

        boolean isTerminal = wf.getStates().stream()
                .anyMatch(s -> s.getName().equals(rws.getCurrentState()) && s.isTerminal());

        List<AllowedTransitionDto> allowed = txRepo
                .findByWorkflowIdAndFromState(wf.getId(), rws.getCurrentState())
                .stream()
                .filter(t -> isRoleAllowed(t.getAllowedRoles(), userRoles))
                .map(t -> AllowedTransitionDto.builder()
                        .triggerName(t.getTriggerName())
                        .triggerLabel(t.getTriggerLabel())
                        .toState(t.getToState())
                        .requiresApproval(t.isRequiresApproval())
                        .build())
                .toList();

        return RecordWorkflowStatusResponse.builder()
                .recordId(recordId)
                .currentState(rws.getCurrentState())
                .objectType(rws.getObjectType())
                .workflowId(wf.getId())
                .isTerminal(isTerminal)
                .allowedTransitions(allowed)
                .updatedAt(rws.getUpdatedAt())
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private WorkflowDefinition resolveWorkflow(String objectType, UUID orgId) {
        List<WorkflowDefinition> matches = wfRepo.findForObjectType(objectType.toUpperCase(), orgId);
        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("WorkflowDefinition", "objectType=" + objectType);
        }
        return matches.getFirst(); // org-scoped wins over platform-level per ORDER BY
    }

    private RecordWorkflowState initState(UUID recordId, UUID orgId, String objectType,
                                          WorkflowDefinition wf) {
        RecordWorkflowState rws = new RecordWorkflowState();
        rws.setRecordId(recordId);
        rws.setOrgId(orgId);
        rws.setObjectType(objectType.toUpperCase());
        rws.setWorkflow(wf);
        rws.setCurrentState(wf.getInitialStatus().toUpperCase());
        return rwsRepo.save(rws);
    }

    private void recordHistory(RecordWorkflowState rws, WorkflowTransition tx,
                               String fromState, UUID actorId, String comment) {
        TransitionHistory h = new TransitionHistory();
        h.setRecordId(rws.getRecordId());
        h.setOrgId(rws.getOrgId());
        h.setWorkflowId(rws.getWorkflow().getId());
        h.setFromState(fromState);
        h.setToState(tx.getToState());
        h.setTriggerName(tx.getTriggerName());
        h.setComment(comment);
        h.setTransitionedBy(actorId);
        histRepo.save(h);
    }

    private boolean isRoleAllowed(List<String> allowedRoles, Set<String> userRoles) {
        if (allowedRoles == null || allowedRoles.isEmpty()) return true;
        if (userRoles == null) return false;
        // PLATFORM_ADMIN always allowed
        if (userRoles.contains("PLATFORM_ADMIN")) return true;
        return allowedRoles.stream().anyMatch(userRoles::contains);
    }

    private Set<String> resolveUserRoles(RecordEvent event) {
        // Roles are embedded in the event by the record-service from the gateway header
        return Set.of(); // overridden by consumer which sets roles from the original request
    }
}
