package com.lagu.platform.automation.event;

import com.lagu.platform.automation.domain.AutomationRunRepository;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.domain.TriggerDefinitionRepository;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.automation.service.AutomationEventParser;
import com.lagu.platform.automation.service.AutomationExecutor;
import com.lagu.platform.automation.service.ConditionEvaluator;
import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformEventConsumer {

    // Guards against an action (e.g. UPDATE_STATUS) re-publishing an event that re-fires the
    // same trigger on the same record indefinitely (e.g. a trigger whose own action flips status
    // back into the state that fired it). Not a legitimate use case at this volume — a trigger
    // firing on one record more than this within the window is treated as a runaway loop.
    private static final int MAX_RUNS_PER_WINDOW = 5;
    private static final Duration LOOP_WINDOW = Duration.ofSeconds(60);

    private final AutomationEventParser       parser;
    private final TriggerDefinitionRepository triggerRepo;
    private final ConditionEvaluator          conditionEvaluator;
    private final AutomationExecutor          executor;
    private final AutomationRunRepository     runRepository;

    @KafkaListener(topics = PlatformTopics.RECORD_EVENTS, groupId = "automation-service")
    public void handleRecordEvent(String payload, Acknowledgment ack) {
        AutomationEventContext ctx = parser.parseRecordEvent(payload);
        if (ctx != null) dispatch(ctx);
        ack.acknowledge();
    }

    @KafkaListener(topics = PlatformTopics.WORKFLOW_EVENTS, groupId = "automation-service-workflow")
    public void handleWorkflowEvent(String payload, Acknowledgment ack) {
        AutomationEventContext ctx = parser.parseWorkflowEvent(payload);
        if (ctx != null) dispatch(ctx);
        ack.acknowledge();
    }

    private void dispatch(AutomationEventContext ctx) {
        if (ctx.getOrgId() == null) return;

        List<com.lagu.platform.automation.domain.TriggerDefinition> triggers =
                ctx.getObjectType() != null
                        ? triggerRepo.findActiveByEventAndType(ctx.getEventType(), ctx.getOrgId(), ctx.getObjectType())
                        : triggerRepo.findActiveByEvent(ctx.getEventType(), ctx.getOrgId());

        log.debug("Event {} matched {} trigger(s)", ctx.getEventType(), triggers.size());

        for (var trigger : triggers) {
            if (!conditionEvaluator.matches(trigger.getConditions(), ctx)) continue;
            if (isRunawayLoop(trigger, ctx)) {
                log.error("Automation loop guard tripped: trigger {} fired more than {} times in " +
                        "{}s for record {} — skipping this run", trigger.getId(), MAX_RUNS_PER_WINDOW,
                        LOOP_WINDOW.toSeconds(), ctx.getRecordId());
                continue;
            }
            executor.execute(trigger, ctx);
        }
    }

    private boolean isRunawayLoop(TriggerDefinition trigger, AutomationEventContext ctx) {
        if (ctx.getRecordId() == null) return false;
        long recentRuns = runRepository.countRecentRuns(
                trigger.getId(), ctx.getRecordId(), Instant.now().minus(LOOP_WINDOW));
        return recentRuns >= MAX_RUNS_PER_WINDOW;
    }
}
