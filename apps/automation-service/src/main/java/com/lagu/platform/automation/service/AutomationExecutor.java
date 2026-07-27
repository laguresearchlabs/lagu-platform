package com.lagu.platform.automation.service;

import com.lagu.platform.automation.domain.*;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.events.AutomationEvent;
import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationExecutor {

    private final ActionExecutor               actionExecutor;
    private final AutomationRunService         runService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Runs synchronously (previously {@code @Async}) — PlatformEventConsumer acks the Kafka
     * offset right after calling this, so an async fire-and-forget call let the offset commit
     * before the automation even started running: a failure here was never retried and never
     * reached the DLT, both configured and both silently dead as a result. A slow trigger's
     * actions now hold up that consumer thread instead, which is the correct trade for actually
     * being able to retry/DLT a failure — throughput can be recovered later with more consumer
     * concurrency, not by making failures unobservable.
     */
    public void execute(TriggerDefinition trigger, AutomationEventContext ctx) {
        ctx.setTriggerId(trigger.getId());
        ctx.setTriggerName(trigger.getName());
        publishTriggerFired(ctx);
        AutomationRun run = runService.createRun(trigger, ctx);

        try {
            List<ActionDefinition> actions = trigger.getActions();
            if (actions == null || actions.isEmpty()) {
                runService.complete(run, "SUCCESS");
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
            runService.complete(run, overallSuccess ? "SUCCESS" : "FAILED");

        } catch (Exception e) {
            log.error("AutomationExecutor failed for trigger {}: {}", trigger.getId(), e.getMessage(), e);
            runService.completeWithError(run, e.getMessage());
            throw e; // propagate so the Kafka listener's error handler can retry/DLT this event
        }
    }

    private void publishTriggerFired(AutomationEventContext ctx) {
        boolean isEscalation = "APPROVAL_TIMEOUT".equals(ctx.getEventType());
        AutomationEvent event = AutomationEvent.builder()
                .eventType(isEscalation ? "ESCALATION_FIRED" : "TRIGGER_FIRED")
                .tenantId(ctx.getTenantId())
                .triggerId(ctx.getTriggerId())
                .triggerName(ctx.getTriggerName())
                .recordId(ctx.getRecordId())
                .objectType(ctx.getObjectType())
                .success(true)
                .occurredAt(Instant.now())
                .build();
        String key = ctx.getTenantId() != null
                ? (ctx.getRecordId() != null ? ctx.getTenantId() + ":" + ctx.getRecordId() : ctx.getTenantId().toString())
                : "platform";
        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, key, event);
    }
}
