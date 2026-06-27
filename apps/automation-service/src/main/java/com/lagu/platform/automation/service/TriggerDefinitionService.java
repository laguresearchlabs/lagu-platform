package com.lagu.platform.automation.service;

import com.lagu.platform.automation.domain.*;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.security.GatewayHeaderFilter;
import lombok.RequiredArgsConstructor;
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
        UUID orgId = GatewayHeaderFilter.current().getOrgId();
        return triggerRepo.findAllForOrg(orgId, pageable);
    }

    public TriggerDefinition getById(UUID id) {
        UUID orgId = GatewayHeaderFilter.current().getOrgId();
        return triggerRepo.findByIdAndOrg(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("TriggerDefinition", id.toString()));
    }

    public TriggerDefinition create(Map<String, Object> req) {
        UUID orgId = GatewayHeaderFilter.current().getOrgId();

        TriggerDefinition trigger = new TriggerDefinition();
        trigger.setOrgId(orgId);
        applyFields(trigger, req);
        return triggerRepo.save(trigger);
    }

    public TriggerDefinition update(UUID id, Map<String, Object> req) {
        TriggerDefinition trigger = getById(id);
        applyFields(trigger, req);
        return triggerRepo.save(trigger);
    }

    public void disable(UUID id) {
        TriggerDefinition trigger = getById(id);
        trigger.setActive(false);
        triggerRepo.save(trigger);
    }

    // ── action management ─────────────────────────────────────────────────────

    public ActionDefinition addAction(UUID triggerId, Map<String, Object> req) {
        TriggerDefinition trigger = getById(triggerId);

        ActionDefinition action = new ActionDefinition();
        action.setTrigger(trigger);
        applyActionFields(action, req);
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
        UUID orgId = GatewayHeaderFilter.current().getOrgId();

        AutomationEventContext ctx = AutomationEventContext.builder()
                .eventType(trigger.getEventType())
                .orgId(orgId)
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
