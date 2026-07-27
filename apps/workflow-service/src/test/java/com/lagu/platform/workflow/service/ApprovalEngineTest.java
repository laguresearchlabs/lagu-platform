package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.workflow.domain.*;
import com.lagu.platform.workflow.dto.ApprovalDecisionRequest;
import com.lagu.platform.workflow.event.WorkflowEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure-unit tests for the approval decision semantics: per-step role enforcement,
 * one-decision-per-approver, self-approval rejection, and the PARALLEL completion rule
 * (every step approved by a holder of that step's role — one approver cannot complete a
 * multi-approver flow alone).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalEngineTest {

    @Mock ApprovalInstanceRepository    instanceRepo;
    @Mock WorkflowEventPublisher        publisher;
    @Mock StateMachineEngine            stateMachine;
    @Mock RecordWorkflowStateRepository rwsRepo;

    @InjectMocks ApprovalEngine engine;

    static final UUID ORG      = UUID.randomUUID();
    static final UUID REQUESTER = UUID.randomUUID();
    static final UUID APPROVER_1 = UUID.randomUUID();
    static final UUID APPROVER_2 = UUID.randomUUID();

    ApprovalInstance instance;
    RecordWorkflowState rws;

    @BeforeEach
    void setUp() {
        rws = new RecordWorkflowState();
        rws.setRecordId(UUID.randomUUID());
        rws.setTenantId(ORG);
        rws.setWorkflow(new WorkflowDefinition());

        when(instanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rwsRepo.findByRecordId(any())).thenReturn(Optional.of(rws));
    }

    private ApprovalInstance newInstance(String approvalType, String... stepRoles) {
        ApprovalDefinition def = new ApprovalDefinition();
        def.setApprovalType(approvalType);
        for (int i = 0; i < stepRoles.length; i++) {
            ApprovalStep step = new ApprovalStep();
            step.setStepOrder(i + 1);
            step.setStepLabel("Step " + (i + 1));
            step.setApproverRole(stepRoles[i]);
            step.setApprovalDefinition(def);
            def.getSteps().add(step);
        }
        ApprovalInstance ai = new ApprovalInstance();
        ai.setId(UUID.randomUUID());
        ai.setRecordId(rws.getRecordId());
        ai.setTenantId(ORG);
        ai.setApprovalDefinition(def);
        ai.setTransition(new WorkflowTransition());
        ai.setStatus("PENDING");
        ai.setCurrentStep(1);
        ai.setRequestedBy(REQUESTER);
        when(instanceRepo.findById(ai.getId())).thenReturn(Optional.of(ai));
        return ai;
    }

    private static ApprovalDecisionRequest decision(String outcome) {
        ApprovalDecisionRequest req = new ApprovalDecisionRequest();
        req.setDecision(outcome);
        return req;
    }

    // ── PARALLEL ──────────────────────────────────────────────────────────────

    @Test
    void parallel_oneApproverCannotCompleteMultiApproverFlow() {
        instance = newInstance("PARALLEL", "ORG_MANAGER", "COMPLIANCE_OFFICER");

        // Approver holds both roles but may decide only once.
        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_1, ORG,
                Set.of("ORG_MANAGER", "COMPLIANCE_OFFICER"));
        assertThat(instance.getStatus()).isEqualTo("PENDING");
        verify(stateMachine, never()).executeTransition(any(), any(), any(), any());

        assertThatThrownBy(() -> engine.decide(instance.getId(), decision("APPROVED"),
                APPROVER_1, ORG, Set.of("ORG_MANAGER", "COMPLIANCE_OFFICER")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already decided");
    }

    @Test
    void parallel_completesWhenEachStepApprovedByItsRoleHolder() {
        instance = newInstance("PARALLEL", "ORG_MANAGER", "COMPLIANCE_OFFICER");

        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_1, ORG, Set.of("ORG_MANAGER"));
        assertThat(instance.getStatus()).isEqualTo("PENDING");

        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_2, ORG, Set.of("COMPLIANCE_OFFICER"));
        assertThat(instance.getStatus()).isEqualTo("APPROVED");
        verify(stateMachine).executeTransition(eq(rws), eq(instance.getTransition()),
                eq(APPROVER_2), any());
    }

    @Test
    void parallel_actorWithNoRemainingStepRoleIsForbidden() {
        instance = newInstance("PARALLEL", "ORG_MANAGER", "COMPLIANCE_OFFICER");

        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_1, ORG, Set.of("ORG_MANAGER"));

        // Second manager can't approve the compliance step.
        assertThatThrownBy(() -> engine.decide(instance.getId(), decision("APPROVED"),
                APPROVER_2, ORG, Set.of("ORG_MANAGER")))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("APPROVAL_ROLE_REQUIRED");
    }

    // ── SEQUENTIAL ────────────────────────────────────────────────────────────

    @Test
    void sequential_enforcesCurrentStepRoleAndAdvances() {
        instance = newInstance("SEQUENTIAL", "ORG_MANAGER", "ORG_OWNER");

        // Step 2's role can't jump the queue.
        assertThatThrownBy(() -> engine.decide(instance.getId(), decision("APPROVED"),
                APPROVER_2, ORG, Set.of("ORG_OWNER")))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("APPROVAL_ROLE_REQUIRED");

        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_1, ORG, Set.of("ORG_MANAGER"));
        assertThat(instance.getCurrentStep()).isEqualTo(2);
        assertThat(instance.getStatus()).isEqualTo("PENDING");

        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_2, ORG, Set.of("ORG_OWNER"));
        assertThat(instance.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void anyOne_singleEligibleApproverCompletes() {
        instance = newInstance("ANY_ONE", "ORG_MANAGER", "ORG_OWNER");

        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_1, ORG, Set.of("ORG_OWNER"));
        assertThat(instance.getStatus()).isEqualTo("APPROVED");
    }

    // ── cross-cutting guards ──────────────────────────────────────────────────

    @Test
    void requesterCannotApproveOwnRequest() {
        instance = newInstance("ANY_ONE", "ORG_MANAGER");

        assertThatThrownBy(() -> engine.decide(instance.getId(), decision("APPROVED"),
                REQUESTER, ORG, Set.of("ORG_MANAGER")))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("SELF_APPROVAL_FORBIDDEN");
    }

    @Test
    void rejectionCompletesInstanceAsRejected() {
        instance = newInstance("PARALLEL", "ORG_MANAGER", "COMPLIANCE_OFFICER");

        engine.decide(instance.getId(), decision("REJECTED"), APPROVER_1, ORG, Set.of("ORG_MANAGER"));
        assertThat(instance.getStatus()).isEqualTo("REJECTED");
        verify(publisher).publishApprovalRejected(any(), eq(rws), eq(instance), eq(APPROVER_1));
        verify(stateMachine, never()).executeTransition(any(), any(), any(), any());
    }

    @Test
    void crossOrgDecisionReadsAsNotFound() {
        instance = newInstance("ANY_ONE", "ORG_MANAGER");

        assertThatThrownBy(() -> engine.decide(instance.getId(), decision("APPROVED"),
                APPROVER_1, UUID.randomUUID(), Set.of("ORG_MANAGER")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void decidedInstanceCannotBeDecidedAgain() {
        instance = newInstance("ANY_ONE", "ORG_MANAGER");
        instance.setStatus("APPROVED");

        assertThatThrownBy(() -> engine.decide(instance.getId(), decision("APPROVED"),
                APPROVER_1, ORG, Set.of("ORG_MANAGER")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already APPROVED");
    }

    @Test
    void listShapedDecisions_recordCorrectStepOrders() {
        instance = newInstance("PARALLEL", "ORG_MANAGER", "COMPLIANCE_OFFICER");

        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_1, ORG, Set.of("ORG_MANAGER"));
        engine.decide(instance.getId(), decision("APPROVED"), APPROVER_2, ORG, Set.of("COMPLIANCE_OFFICER"));

        List<Integer> stepOrders = instance.getDecisions().stream()
                .map(ApprovalStepDecision::getStepOrder).toList();
        assertThat(stepOrders).containsExactlyInAnyOrder(1, 2);
    }
}
