package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.workflow.domain.*;
import com.lagu.platform.workflow.dto.ApprovalDecisionRequest;
import com.lagu.platform.workflow.dto.ApprovalInstanceResponse;
import com.lagu.platform.workflow.event.WorkflowEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalEngine {

    private final ApprovalInstanceRepository   instanceRepo;
    private final WorkflowEventPublisher       publisher;
    private final StateMachineEngine           stateMachine;
    private final RecordWorkflowStateRepository rwsRepo;

    @Transactional
    public void startApproval(RecordWorkflowState rws, WorkflowTransition transition, UUID requestedBy) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setRecordId(rws.getRecordId());
        instance.setOrgId(rws.getOrgId());
        instance.setApprovalDefinition(transition.getApprovalDefinition());
        instance.setTransition(transition);
        instance.setStatus("PENDING");
        instance.setCurrentStep(1);
        ApprovalInstance saved = instanceRepo.save(instance);

        publisher.publishApprovalRequested(rws.getWorkflow(), rws, transition, saved, requestedBy);
        log.info("Approval {} started for record {} via transition {}", saved.getId(),
                rws.getRecordId(), transition.getTriggerName());
    }

    @Transactional
    public ApprovalInstanceResponse decide(UUID instanceId, ApprovalDecisionRequest req, UUID actorId) {
        ApprovalInstance instance = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalInstance", instanceId.toString()));

        if (!"PENDING".equals(instance.getStatus())) {
            throw new ValidationException("Approval instance is already " + instance.getStatus());
        }

        ApprovalDefinition def = instance.getApprovalDefinition();
        int totalSteps = def.getSteps().size();
        int currentStep = instance.getCurrentStep();

        ApprovalStepDecision decision = new ApprovalStepDecision();
        decision.setApprovalInstance(instance);
        decision.setStepOrder(currentStep);
        decision.setApproverUserId(actorId);
        decision.setDecision(req.getDecision().toUpperCase());
        decision.setComment(req.getComment());
        instance.getDecisions().add(decision);

        if ("REJECTED".equals(req.getDecision())) {
            complete(instance, "REJECTED", actorId);
        } else {
            // APPROVED
            switch (def.getApprovalType()) {
                case "ANY_ONE" -> complete(instance, "APPROVED", actorId);
                case "PARALLEL" -> {
                    long approvedCount = instance.getDecisions().stream()
                            .filter(d -> "APPROVED".equals(d.getDecision())).count();
                    if (approvedCount >= totalSteps) complete(instance, "APPROVED", actorId);
                }
                default -> { // SEQUENTIAL
                    if (currentStep >= totalSteps) {
                        complete(instance, "APPROVED", actorId);
                    } else {
                        instance.setCurrentStep(currentStep + 1);
                        instanceRepo.save(instance);
                        publisher.publishApprovalStepCompleted(instance, currentStep, actorId);
                    }
                }
            }
        }

        return toResponse(instanceRepo.save(instance));
    }

    private void complete(ApprovalInstance instance, String outcome, UUID actorId) {
        instance.setStatus(outcome);
        instance.setCompletedAt(OffsetDateTime.now());

        RecordWorkflowState rws = rwsRepo.findByRecordId(instance.getRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowState", instance.getRecordId().toString()));

        if ("APPROVED".equals(outcome)) {
            stateMachine.executeTransition(rws, instance.getTransition(), actorId, "Approved");
        } else {
            publisher.publishApprovalRejected(rws.getWorkflow(), rws, instance, actorId);
        }
    }

    public List<ApprovalInstanceResponse> getPendingForUser(Set<String> roles, Integer olderThanMinutes) {
        List<String> roleList = List.copyOf(roles);
        List<ApprovalInstance> instances = olderThanMinutes != null
                ? instanceRepo.findPendingForRolesOlderThan(roleList, OffsetDateTime.now().minusMinutes(olderThanMinutes))
                : instanceRepo.findPendingForRoles(roleList);
        return instances.stream().map(this::toResponse).toList();
    }

    public ApprovalInstanceResponse getById(UUID id) {
        return toResponse(instanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalInstance", id.toString())));
    }

    private ApprovalInstanceResponse toResponse(ApprovalInstance ai) {
        ApprovalDefinition def = ai.getApprovalDefinition();
        int totalSteps = def.getSteps().size();
        String currentRole = (ai.getCurrentStep() <= totalSteps && !"PENDING".equals(ai.getStatus()))
                ? null
                : def.getSteps().stream()
                        .filter(s -> s.getStepOrder() == ai.getCurrentStep())
                        .map(ApprovalStep::getApproverRole)
                        .findFirst().orElse(null);

        List<ApprovalInstanceResponse.StepDecisionDto> decisions = ai.getDecisions().stream()
                .map(d -> ApprovalInstanceResponse.StepDecisionDto.builder()
                        .stepOrder(d.getStepOrder()).approverUserId(d.getApproverUserId())
                        .decision(d.getDecision()).comment(d.getComment()).decidedAt(d.getDecidedAt())
                        .build())
                .toList();

        return ApprovalInstanceResponse.builder()
                .id(ai.getId()).recordId(ai.getRecordId()).status(ai.getStatus())
                .currentStep(ai.getCurrentStep()).totalSteps(totalSteps)
                .approvalType(def.getApprovalType()).currentApproverRole(currentRole)
                .decisions(decisions).createdAt(ai.getCreatedAt()).completedAt(ai.getCompletedAt())
                .build();
    }
}
