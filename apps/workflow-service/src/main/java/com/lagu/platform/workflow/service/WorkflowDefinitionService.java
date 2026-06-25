package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.workflow.domain.*;
import com.lagu.platform.workflow.dto.*;
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
public class WorkflowDefinitionService {

    private final WorkflowDefinitionRepository   wfRepo;
    private final WorkflowTransitionRepository   txRepo;
    private final ApprovalDefinitionRepository   approvalDefRepo;

    public List<WorkflowDefinitionResponse> listAll() {
        return wfRepo.findByActiveTrue().stream().map(w -> toResponse(w, false)).toList();
    }

    public WorkflowDefinitionResponse getById(UUID id) {
        return toResponse(findById(id), true);
    }

    @Transactional
    public WorkflowDefinitionResponse create(WorkflowDefinitionRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setOrgId(ctx != null && !ctx.isPlatformAdmin() ? ctx.getOrgId() : null);
        wf.setName(req.getName());
        wf.setLabel(req.getLabel());
        wf.setObjectType(req.getObjectType().toUpperCase());
        wf.setInitialStatus(req.getInitialStatus().toUpperCase());
        return toResponse(wfRepo.save(wf), false);
    }

    @Transactional
    public WorkflowDefinitionResponse addState(UUID wfId, WorkflowStateRequest req) {
        WorkflowDefinition wf = findById(wfId);
        WorkflowState state = new WorkflowState();
        state.setWorkflow(wf);
        state.setName(req.getName().toUpperCase());
        state.setLabel(req.getLabel());
        state.setDescription(req.getDescription());
        state.setTerminal(req.isTerminal());
        state.setDisplayOrder(req.getDisplayOrder());
        state.setColor(req.getColor());
        wf.getStates().add(state);
        return toResponse(wfRepo.save(wf), true);
    }

    @Transactional
    public WorkflowDefinitionResponse addTransition(UUID wfId, WorkflowTransitionRequest req) {
        WorkflowDefinition wf = findById(wfId);

        boolean fromExists = wf.getStates().stream()
                .anyMatch(s -> s.getName().equals(req.getFromState().toUpperCase()));
        boolean toExists = wf.getStates().stream()
                .anyMatch(s -> s.getName().equals(req.getToState().toUpperCase()));
        if (!fromExists || !toExists) {
            throw new ValidationException("Both from_state and to_state must exist on the workflow");
        }

        WorkflowTransition tx = new WorkflowTransition();
        tx.setWorkflow(wf);
        tx.setFromState(req.getFromState().toUpperCase());
        tx.setToState(req.getToState().toUpperCase());
        tx.setTriggerName(req.getTriggerName().toUpperCase());
        tx.setTriggerLabel(req.getTriggerLabel());
        tx.setAllowedRoles(req.getAllowedRoles());
        tx.setRequiresApproval(req.isRequiresApproval());

        if (req.isRequiresApproval() && req.getApprovalDefId() != null) {
            ApprovalDefinition appDef = approvalDefRepo.findById(req.getApprovalDefId())
                    .orElseThrow(() -> new ResourceNotFoundException("ApprovalDefinition", req.getApprovalDefId().toString()));
            tx.setApprovalDefinition(appDef);
        }

        wf.getTransitions().add(tx);
        return toResponse(wfRepo.save(wf), true);
    }

    WorkflowDefinition findById(UUID id) {
        return wfRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowDefinition", id.toString()));
    }

    WorkflowDefinitionResponse toResponse(WorkflowDefinition w, boolean detail) {
        List<WorkflowStateResponse> states = detail
                ? w.getStates().stream().map(this::toStateResponse).toList() : List.of();
        List<WorkflowTransitionResponse> transitions = detail
                ? w.getTransitions().stream().map(this::toTxResponse).toList() : List.of();
        return WorkflowDefinitionResponse.builder()
                .id(w.getId()).orgId(w.getOrgId()).name(w.getName()).label(w.getLabel())
                .objectType(w.getObjectType()).initialStatus(w.getInitialStatus())
                .active(w.isActive()).states(states).transitions(transitions)
                .createdAt(w.getCreatedAt()).updatedAt(w.getUpdatedAt())
                .build();
    }

    WorkflowStateResponse toStateResponse(WorkflowState s) {
        return WorkflowStateResponse.builder()
                .id(s.getId()).name(s.getName()).label(s.getLabel())
                .description(s.getDescription()).terminal(s.isTerminal())
                .displayOrder(s.getDisplayOrder()).color(s.getColor())
                .build();
    }

    WorkflowTransitionResponse toTxResponse(WorkflowTransition t) {
        return WorkflowTransitionResponse.builder()
                .id(t.getId()).fromState(t.getFromState()).toState(t.getToState())
                .triggerName(t.getTriggerName()).triggerLabel(t.getTriggerLabel())
                .allowedRoles(t.getAllowedRoles()).requiresApproval(t.isRequiresApproval())
                .approvalDefId(t.getApprovalDefinition() != null ? t.getApprovalDefinition().getId() : null)
                .build();
    }
}
