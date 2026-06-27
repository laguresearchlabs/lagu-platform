package com.lagu.platform.automation.service;

import com.lagu.platform.automation.domain.*;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.events.AutomationEvent;
import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationExecutor {

    private final ActionExecutor               actionExecutor;
    private final AutomationRunRepository      runRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Async
    public void execute(TriggerDefinition trigger, AutomationEventContext ctx) {
        ctx.setTriggerId(trigger.getId());
        ctx.setTriggerName(trigger.getName());
        publishTriggerFired(ctx);
        AutomationRun run = createRun(trigger, ctx);

        try {
            List<ActionDefinition> actions = trigger.getActions();
            if (actions == null || actions.isEmpty()) {
                complete(run, "SUCCESS");
                return;
            }

            List<ActionRun> actionRuns = new ArrayList<>();
            boolean overallSuccess     = true;

            for (ActionDefinition action : actions) {
                if (!action.isActive()) continue;

                ActionRun ar = new ActionRun();
                ar.setAutomationRun(run);
                ar.setAction(action);
                ar.setActionType(action.getActionType());

                boolean ok = actionExecutor.execute(action, ctx);
                ar.setStatus(ok ? "SUCCESS" : "FAILED");
                actionRuns.add(ar);

                if (!ok) {
                    overallSuccess = false;
                    if (!action.isContinueOnFailure()) break;
                }
            }

            run.setActionRuns(actionRuns);
            complete(run, overallSuccess ? "SUCCESS" : "FAILED");

        } catch (Exception e) {
            log.error("AutomationExecutor failed for trigger {}: {}", trigger.getId(), e.getMessage(), e);
            run.setErrorMessage(e.getMessage());
            complete(run, "FAILED");
        }
    }

    @Transactional
    protected AutomationRun createRun(TriggerDefinition trigger, AutomationEventContext ctx) {
        AutomationRun run = new AutomationRun();
        run.setTrigger(trigger);
        run.setOrgId(ctx.getOrgId());
        run.setRecordId(ctx.getRecordId());
        run.setEventType(ctx.getEventType());
        run.setStatus("RUNNING");
        return runRepository.save(run);
    }

    @Transactional
    protected void complete(AutomationRun run, String status) {
        run.setStatus(status);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
    }

    private void publishTriggerFired(AutomationEventContext ctx) {
        boolean isEscalation = "APPROVAL_TIMEOUT".equals(ctx.getEventType());
        AutomationEvent event = AutomationEvent.builder()
                .eventType(isEscalation ? "ESCALATION_FIRED" : "TRIGGER_FIRED")
                .orgId(ctx.getOrgId())
                .triggerId(ctx.getTriggerId())
                .triggerName(ctx.getTriggerName())
                .recordId(ctx.getRecordId())
                .objectType(ctx.getObjectType())
                .success(true)
                .occurredAt(Instant.now())
                .build();
        String key = ctx.getOrgId() != null
                ? (ctx.getRecordId() != null ? ctx.getOrgId() + ":" + ctx.getRecordId() : ctx.getOrgId().toString())
                : "platform";
        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, key, event);
    }
}
