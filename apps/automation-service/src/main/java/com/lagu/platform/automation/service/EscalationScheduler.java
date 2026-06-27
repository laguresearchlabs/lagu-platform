package com.lagu.platform.automation.service;

import com.lagu.platform.automation.client.WorkflowServiceClient;
import com.lagu.platform.automation.domain.TriggerDefinitionRepository;
import com.lagu.platform.automation.model.AutomationEventContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class EscalationScheduler {

    private final WorkflowServiceClient       workflowClient;
    private final TriggerDefinitionRepository triggerRepo;
    private final ConditionEvaluator          conditionEvaluator;
    private final AutomationExecutor          executor;

    @Value("${platform.automation.approval-timeout-minutes:60}")
    private int approvalTimeoutMinutes;

    @Scheduled(fixedDelayString = "${platform.automation.approval-timeout-check-interval-ms:60000}")
    public void checkApprovalTimeouts() {
        log.debug("Checking for approval timeouts older than {} minutes", approvalTimeoutMinutes);

        List<Map<String, Object>> timedOut = workflowClient.fetchTimedOutApprovals(approvalTimeoutMinutes);
        if (timedOut == null || timedOut.isEmpty()) return;

        log.info("Found {} timed-out approval(s)", timedOut.size());

        for (Map<String, Object> instance : timedOut) {
            AutomationEventContext ctx = buildTimeoutContext(instance);
            if (ctx == null) continue;

            var triggers = triggerRepo.findActiveByEvent("APPROVAL_TIMEOUT", ctx.getOrgId());
            for (var trigger : triggers) {
                if (conditionEvaluator.matches(trigger.getConditions(), ctx)) {
                    executor.execute(trigger, ctx);
                }
            }
        }
    }

    private AutomationEventContext buildTimeoutContext(Map<String, Object> instance) {
        try {
            UUID orgId    = instance.get("orgId")    != null ? UUID.fromString(String.valueOf(instance.get("orgId")))    : null;
            UUID recordId = instance.get("recordId") != null ? UUID.fromString(String.valueOf(instance.get("recordId"))) : null;
            UUID instanceId = instance.get("id")     != null ? UUID.fromString(String.valueOf(instance.get("id")))       : null;

            return AutomationEventContext.builder()
                    .eventType("APPROVAL_TIMEOUT")
                    .orgId(orgId)
                    .recordId(recordId)
                    .objectType((String) instance.get("objectType"))
                    .approvalInstanceId(instanceId)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to build timeout context: {}", e.getMessage());
            return null;
        }
    }
}
