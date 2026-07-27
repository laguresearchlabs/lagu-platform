package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.workflow.domain.*;
import com.lagu.platform.workflow.dto.ApprovalDecisionRequest;
import com.lagu.platform.workflow.dto.ApprovalInstanceResponse;
import com.lagu.platform.workflow.event.WorkflowEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
        instance.setTenantId(rws.getTenantId());
        instance.setApprovalDefinition(transition.getApprovalDefinition());
        instance.setTransition(transition);
        instance.setStatus("PENDING");
        instance.setCurrentStep(1);
        instance.setRequestedBy(requestedBy);
        ApprovalInstance saved = instanceRepo.save(instance);

        publisher.publishApprovalRequested(rws.getWorkflow(), rws, transition, saved, requestedBy);
        log.info("Approval {} started for record {} via transition {}", saved.getId(),
                rws.getRecordId(), transition.getTriggerName());
    }

    @Transactional
    public ApprovalInstanceResponse decide(UUID instanceId, ApprovalDecisionRequest req, UUID actorId,
                                            UUID actorTenantId, Set<String> actorRoles) {
        ApprovalInstance instance = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalInstance", instanceId.toString()));

        // Tenant isolation: an approval instance belongs to the org that owns the underlying
        // record. Treat a cross-org lookup the same as "not found" rather than leaking existence.
        if (!instance.getTenantId().equals(actorTenantId)) {
            throw new ResourceNotFoundException("ApprovalInstance", instanceId.toString());
        }

        if (!"PENDING".equals(instance.getStatus())) {
            throw new ValidationException("Approval instance is already " + instance.getStatus());
        }

        // A multi-step approval exists to gather independent judgements: the requester may not
        // approve their own request, and no approver may decide more than once per instance.
        if (actorId != null && actorId.equals(instance.getRequestedBy())) {
            throw new PlatformException("SELF_APPROVAL_FORBIDDEN",
                    "The requester of a transition cannot decide its approval", HttpStatus.FORBIDDEN);
        }
        boolean alreadyDecided = instance.getDecisions().stream()
                .anyMatch(d -> d.getApproverUserId().equals(actorId));
        if (alreadyDecided) {
            throw new ValidationException("You have already decided on this approval");
        }

        ApprovalDefinition def = instance.getApprovalDefinition();
        int totalSteps = def.getSteps().size();

        // R-06: the approver's role must be checked at decision time, not trusted from when the
        // approval instance was created — a role granted/revoked since then must take effect now.
        int decisionStep = resolveDecisionStep(def, instance, actorRoles);

        ApprovalStepDecision decision = new ApprovalStepDecision();
        decision.setApprovalInstance(instance);
        decision.setStepOrder(decisionStep);
        decision.setApproverUserId(actorId);
        decision.setDecision(req.getDecision().toUpperCase());
        decision.setComment(req.getComment());
        instance.getDecisions().add(decision);

        if ("REJECTED".equalsIgnoreCase(req.getDecision())) {
            complete(instance, "REJECTED", actorId);
        } else {
            // APPROVED
            switch (def.getApprovalType()) {
                case "ANY_ONE" -> complete(instance, "APPROVED", actorId);
                case "PARALLEL" -> {
                    // Complete only when every step has an approval from its own role-holder.
                    Set<Integer> approvedSteps = instance.getDecisions().stream()
                            .filter(d -> "APPROVED".equals(d.getDecision()))
                            .map(ApprovalStepDecision::getStepOrder)
                            .collect(java.util.stream.Collectors.toSet());
                    boolean allApproved = def.getSteps().stream()
                            .allMatch(s -> approvedSteps.contains(s.getStepOrder()));
                    if (allApproved) {
                        complete(instance, "APPROVED", actorId);
                    } else {
                        publisher.publishApprovalStepCompleted(instance, decisionStep, actorId);
                    }
                }
                default -> { // SEQUENTIAL
                    if (decisionStep >= totalSteps) {
                        complete(instance, "APPROVED", actorId);
                    } else {
                        instance.setCurrentStep(decisionStep + 1);
                        publisher.publishApprovalStepCompleted(instance, decisionStep, actorId);
                    }
                }
            }
        }

        return toResponse(instanceRepo.save(instance));
    }

    /**
     * Determines which step this actor is deciding, enforcing per-step approver roles:
     * SEQUENTIAL — the current step only; PARALLEL — any step not yet approved whose role the
     * actor holds (steps are independent and may be approved in any order); ANY_ONE — any step
     * whose role the actor holds.
     */
    private int resolveDecisionStep(ApprovalDefinition def, ApprovalInstance instance,
                                    Set<String> actorRoles) {
        Set<String> roles = actorRoles != null ? actorRoles : Set.of();

        if ("SEQUENTIAL".equals(def.getApprovalType())) {
            int currentStep = instance.getCurrentStep();
            String requiredRole = def.getSteps().stream()
                    .filter(s -> s.getStepOrder() == currentStep)
                    .map(ApprovalStep::getApproverRole)
                    .findFirst()
                    .orElseThrow(() -> new ValidationException(
                            "No approval step configured for step " + currentStep));
            if (!roles.contains(requiredRole)) {
                throw new PlatformException("APPROVAL_ROLE_REQUIRED",
                        "Decision requires role " + requiredRole, HttpStatus.FORBIDDEN);
            }
            return currentStep;
        }

        Set<Integer> approvedSteps = instance.getDecisions().stream()
                .filter(d -> "APPROVED".equals(d.getDecision()))
                .map(ApprovalStepDecision::getStepOrder)
                .collect(java.util.stream.Collectors.toSet());

        return def.getSteps().stream()
                .filter(s -> "ANY_ONE".equals(def.getApprovalType())
                        || !approvedSteps.contains(s.getStepOrder()))
                .filter(s -> roles.contains(s.getApproverRole()))
                .map(ApprovalStep::getStepOrder)
                .findFirst()
                .orElseThrow(() -> new PlatformException("APPROVAL_ROLE_REQUIRED",
                        "None of your roles can decide a remaining step of this approval",
                        HttpStatus.FORBIDDEN));
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

    public List<ApprovalInstanceResponse> getPendingForUser(UUID tenantId, Set<String> roles, Integer olderThanMinutes) {
        List<String> roleList = List.copyOf(roles);
        List<ApprovalInstance> instances = olderThanMinutes != null
                ? instanceRepo.findPendingForRolesOlderThan(tenantId, roleList, OffsetDateTime.now().minusMinutes(olderThanMinutes))
                : instanceRepo.findPendingForRoles(tenantId, roleList);
        return instances.stream().map(this::toResponse).toList();
    }

    /** Platform-wide (cross-org, no role filter) — for automation-service's escalation scheduler. */
    public List<ApprovalInstanceResponse> getAllTimedOut(int olderThanMinutes) {
        List<ApprovalInstance> instances =
                instanceRepo.findPendingOlderThan(OffsetDateTime.now().minusMinutes(olderThanMinutes));
        return instances.stream().map(this::toResponse).toList();
    }

    public ApprovalInstanceResponse getById(UUID id, UUID tenantId) {
        ApprovalInstance instance = instanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalInstance", id.toString()));
        if (!instance.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("ApprovalInstance", id.toString());
        }
        return toResponse(instance);
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
                .id(ai.getId()).recordId(ai.getRecordId()).tenantId(ai.getTenantId()).status(ai.getStatus())
                .currentStep(ai.getCurrentStep()).totalSteps(totalSteps)
                .approvalType(def.getApprovalType()).currentApproverRole(currentRole)
                .decisions(decisions).createdAt(ai.getCreatedAt()).completedAt(ai.getCompletedAt())
                .build();
    }
}
