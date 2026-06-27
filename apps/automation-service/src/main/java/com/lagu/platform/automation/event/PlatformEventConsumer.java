package com.lagu.platform.automation.event;

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

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformEventConsumer {

    private final AutomationEventParser       parser;
    private final TriggerDefinitionRepository triggerRepo;
    private final ConditionEvaluator          conditionEvaluator;
    private final AutomationExecutor          executor;

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
            if (conditionEvaluator.matches(trigger.getConditions(), ctx)) {
                executor.execute(trigger, ctx);
            }
        }
    }
}
