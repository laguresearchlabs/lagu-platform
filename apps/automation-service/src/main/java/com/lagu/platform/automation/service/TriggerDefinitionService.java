package com.lagu.platform.automation.service;

import com.lagu.platform.automation.domain.*;
import com.lagu.platform.automation.dto.CreateActionRequest;
import com.lagu.platform.automation.dto.CreateTriggerRequest;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.security.GatewayHeaderFilter;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class TriggerDefinitionService {

    private final TriggerDefinitionRepository triggerRepo;
    private final ActionDefinitionRepository  actionRepo;
    private final ConditionEvaluator          conditionEvaluator;
    private final AutomationExecutor          executor;

    public Page<TriggerDefinition> listForOrg(Pageable pageable) {
        var ctx = GatewayHeaderFilter.current();
        // findAllForOrg(null, ...) degrades to "platform-level triggers only" (tenantId = NULL
        // matches nothing in SQL), not "every org" — a platform admin needs the genuinely
        // unscoped query instead.
        Page<TriggerDefinition> page = (ctx != null && ctx.isPlatformAdmin())
                ? triggerRepo.findAll(pageable)
                : triggerRepo.findAllForOrg(ctx.getTenantId(), pageable);
        return page.map(this::withActionsInitialized);
    }

    public TriggerDefinition getById(UUID id) {
        var ctx = GatewayHeaderFilter.current();
        TriggerDefinition trigger = (ctx != null && ctx.isPlatformAdmin())
                ? triggerRepo.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("TriggerDefinition", id.toString()))
                : triggerRepo.findByIdAndOrg(id, ctx.getTenantId())
                        .orElseThrow(() -> new ResourceNotFoundException("TriggerDefinition", id.toString()));
        return withActionsInitialized(trigger);
    }

    public TriggerDefinition create(CreateTriggerRequest req) {
        UUID tenantId = GatewayHeaderFilter.current().getTenantId();

        TriggerDefinition trigger = new TriggerDefinition();
        trigger.setTenantId(tenantId);
        trigger.setName(req.getName());
        trigger.setLabel(req.getLabel());
        trigger.setDescription(req.getDescription());
        trigger.setEventType(req.getEventType());
        trigger.setObjectType(req.getObjectType());
        trigger.setConditions(req.getConditions());
        if (req.getIsActive() != null) trigger.setActive(req.getIsActive());
        return withActionsInitialized(triggerRepo.save(trigger));
    }

    public TriggerDefinition update(UUID id, Map<String, Object> req) {
        TriggerDefinition trigger = getById(id);
        applyFields(trigger, req);
        return withActionsInitialized(triggerRepo.save(trigger));
    }

    /** TriggerController returns this entity directly (no DTO layer) and open-in-view is
     *  intentionally false, so the "actions" collection must be pulled while the transactional
     *  service method (and its Hibernate session) is still open — otherwise Jackson hits a
     *  LazyInitializationException serializing the response after the session has closed. */
    private TriggerDefinition withActionsInitialized(TriggerDefinition trigger) {
        Hibernate.initialize(trigger.getActions());
        return trigger;
    }

    public void disable(UUID id) {
        TriggerDefinition trigger = getById(id);
        trigger.setActive(false);
        triggerRepo.save(trigger);
    }

    // ── action management ─────────────────────────────────────────────────────

    public ActionDefinition addAction(UUID triggerId, CreateActionRequest req) {
        TriggerDefinition trigger = getById(triggerId);

        ActionDefinition action = new ActionDefinition();
        action.setTrigger(trigger);
        action.setActionType(req.getActionType());
        action.setExecutionOrder(req.getExecutionOrder() != null ? req.getExecutionOrder() : 0);
        action.setConfig(req.getConfig());
        if (req.getContinueOnFailure() != null) action.setContinueOnFailure(req.getContinueOnFailure());
        if (req.getIsActive() != null) action.setActive(req.getIsActive());
        return actionRepo.save(action);
    }

    public ActionDefinition updateAction(UUID triggerId, UUID actionId, Map<String, Object> req) {
        getById(triggerId);  // validates ownership
        ActionDefinition action = actionRepo.findById(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionDefinition", actionId.toString()));
        applyActionFields(action, req);
        return actionRepo.save(action);
    }

    public void removeAction(UUID triggerId, UUID actionId) {
        getById(triggerId);
        actionRepo.deleteById(actionId);
    }

    // ── test / dry-run ────────────────────────────────────────────────────────

    public void dryRun(UUID triggerId, Map<String, Object> sampleData) {
        TriggerDefinition trigger = getById(triggerId);
        UUID tenantId = GatewayHeaderFilter.current().getTenantId();

        AutomationEventContext ctx = AutomationEventContext.builder()
                .eventType(trigger.getEventType())
                .tenantId(tenantId)
                .objectType(trigger.getObjectType())
                .data(sampleData)
                .dryRun(true)
                .build();

        executor.execute(trigger, ctx);
    }

    @SuppressWarnings("unchecked")
    private void applyFields(TriggerDefinition t, Map<String, Object> req) {
        if (req.containsKey("name"))        t.setName((String) req.get("name"));
        if (req.containsKey("label"))       t.setLabel((String) req.get("label"));
        if (req.containsKey("description")) t.setDescription((String) req.get("description"));
        if (req.containsKey("eventType"))   t.setEventType((String) req.get("eventType"));
        if (req.containsKey("objectType"))  t.setObjectType((String) req.get("objectType"));
        if (req.containsKey("conditions"))  t.setConditions((List<Map<String, Object>>) req.get("conditions"));
        if (req.containsKey("isActive"))    t.setActive(Boolean.TRUE.equals(req.get("isActive")));
    }

    @SuppressWarnings("unchecked")
    private void applyActionFields(ActionDefinition a, Map<String, Object> req) {
        if (req.containsKey("actionType"))         a.setActionType((String) req.get("actionType"));
        if (req.containsKey("executionOrder"))     a.setExecutionOrder((Integer) req.get("executionOrder"));
        if (req.containsKey("config"))             a.setConfig((Map<String, Object>) req.get("config"));
        if (req.containsKey("continueOnFailure"))  a.setContinueOnFailure(Boolean.TRUE.equals(req.get("continueOnFailure")));
        if (req.containsKey("isActive"))           a.setActive(Boolean.TRUE.equals(req.get("isActive")));
    }
}
