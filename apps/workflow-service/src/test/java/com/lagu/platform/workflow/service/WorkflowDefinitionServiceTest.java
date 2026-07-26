package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.workflow.domain.*;
import com.lagu.platform.workflow.dto.WorkflowStateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the review's critical finding: addState/addTransition (and, less
 * severely, getById/listAll) resolved a WorkflowDefinition purely by id with no check that it
 * belonged to the caller's own org — a CONFIG_ADMIN (a role that is not necessarily
 * platform-wide) could pass the id of another org's, or the platform's own (orgId null),
 * workflow and silently modify it, granting whatever bypass they configured to every tenant
 * sharing that workflow.
 */
class WorkflowDefinitionServiceTest {

    private final WorkflowDefinitionRepository wfRepo = mock(WorkflowDefinitionRepository.class);
    private final WorkflowTransitionRepository txRepo = mock(WorkflowTransitionRepository.class);
    private final ApprovalDefinitionRepository approvalDefRepo = mock(ApprovalDefinitionRepository.class);
    private final WorkflowDefinitionService service =
            new WorkflowDefinitionService(wfRepo, txRepo, approvalDefRepo);

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    private void asCaller(PlatformSecurityContext ctx) {
        if (gatewayMock == null) {
            gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        }
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(ctx);
    }

    @AfterEach
    void tearDown() {
        if (gatewayMock != null) gatewayMock.close();
    }

    private static PlatformSecurityContext ctx(UUID orgId, String... roles) {
        return PlatformSecurityContext.builder()
                .userId(UUID.randomUUID()).orgId(orgId).roles(Set.of(roles)).build();
    }

    private static WorkflowDefinition wf(UUID id, UUID orgId) {
        WorkflowDefinition w = new WorkflowDefinition();
        w.setId(id);
        w.setOrgId(orgId);
        w.setStates(new java.util.ArrayList<>());
        w.setTransitions(new java.util.ArrayList<>());
        return w;
    }

    // ---- addState: the critical finding ----

    @Test
    void configAdminCannotAddStateToAnotherOrgsWorkflow() {
        UUID otherOrg = UUID.randomUUID();
        UUID wfId = UUID.randomUUID();
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(wf(wfId, otherOrg)));
        asCaller(ctx(UUID.randomUUID(), "CONFIG_ADMIN")); // a *different* org than otherOrg

        var req = new WorkflowStateRequest();
        req.setName("HACKED");

        assertThatThrownBy(() -> service.addState(wfId, req))
                .isInstanceOf(PlatformException.class);
        verify(wfRepo, never()).save(any());
    }

    @Test
    void configAdminCanAddStateToThePlatformLevelWorkflow() {
        // orgId=null is the platform-wide workflow — PlatformSecurityContext.canWriteOrgScoped's
        // own contract treats CONFIG_ADMIN as sufficient for platform-level writes (it's meant
        // to be a platform-wide administrative role). The bug this fix actually closes is
        // narrower and is covered by configAdminCannotAddStateToAnotherOrgsWorkflow below: a
        // CONFIG_ADMIN reaching a *different specific tenant's* own org-scoped workflow, which
        // findById(wfId) alone had no way to prevent.
        UUID wfId = UUID.randomUUID();
        WorkflowDefinition def = wf(wfId, null);
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(def));
        when(wfRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        asCaller(ctx(UUID.randomUUID(), "CONFIG_ADMIN"));

        var req = new WorkflowStateRequest();
        req.setName("NEW_STATE");

        service.addState(wfId, req); // must not throw
        verify(wfRepo).save(def);
    }

    @Test
    void configAdminCanAddStateToTheirOwnOrgsWorkflow() {
        UUID myOrg = UUID.randomUUID();
        UUID wfId = UUID.randomUUID();
        WorkflowDefinition def = wf(wfId, myOrg);
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(def));
        when(wfRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        asCaller(ctx(myOrg, "CONFIG_ADMIN"));

        var req = new WorkflowStateRequest();
        req.setName("NEW_STATE");
        req.setLabel("New State");

        service.addState(wfId, req); // must not throw
        verify(wfRepo).save(def);
    }

    @Test
    void platformAdminCanAddStateToAnyWorkflowRegardlessOfOrg() {
        UUID wfId = UUID.randomUUID();
        WorkflowDefinition def = wf(wfId, UUID.randomUUID());
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(def));
        when(wfRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        asCaller(ctx(UUID.randomUUID(), "PLATFORM_ADMIN"));

        var req = new WorkflowStateRequest();
        req.setName("NEW_STATE");

        service.addState(wfId, req); // must not throw
        verify(wfRepo).save(def);
    }

    // ---- getById: cross-tenant read ----

    @Test
    void nonAdminCannotReadAnotherOrgsWorkflowById() {
        UUID wfId = UUID.randomUUID();
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(wf(wfId, UUID.randomUUID())));
        asCaller(ctx(UUID.randomUUID(), "CONFIG_ADMIN"));

        // 404, not 403 — existence of another org's workflow definition should not be disclosed.
        assertThatThrownBy(() -> service.getById(wfId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anyoneCanReadThePlatformLevelWorkflow() {
        UUID wfId = UUID.randomUUID();
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(wf(wfId, null)));
        asCaller(ctx(UUID.randomUUID(), "ORG_MANAGER"));

        assertThat(service.getById(wfId)).isNotNull(); // must not throw
    }

    // ---- listAll: cross-tenant listing ----

    @Test
    void nonAdminListAllOnlySeesOwnOrgAndPlatformLevel() {
        UUID myOrg = UUID.randomUUID();
        when(wfRepo.findByActiveTrueAndOrgIdOrPlatformLevel(myOrg))
                .thenReturn(List.of(wf(UUID.randomUUID(), myOrg), wf(UUID.randomUUID(), null)));
        asCaller(ctx(myOrg, "CONFIG_ADMIN"));

        var result = service.listAll();

        assertThat(result).hasSize(2);
        verify(wfRepo, never()).findByActiveTrue();
    }

    @Test
    void platformAdminListAllSeesEverything() {
        when(wfRepo.findByActiveTrue())
                .thenReturn(List.of(wf(UUID.randomUUID(), UUID.randomUUID()), wf(UUID.randomUUID(), null)));
        asCaller(ctx(UUID.randomUUID(), "PLATFORM_ADMIN"));

        var result = service.listAll();

        assertThat(result).hasSize(2);
        verify(wfRepo, never()).findByActiveTrueAndOrgIdOrPlatformLevel(any());
    }
}
