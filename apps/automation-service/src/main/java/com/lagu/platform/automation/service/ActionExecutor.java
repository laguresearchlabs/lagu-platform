package com.lagu.platform.automation.service;

import com.lagu.platform.automation.client.RecordServiceClient;
import com.lagu.platform.automation.domain.ActionDefinition;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.events.AutomationEvent;
import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ActionExecutor {

    private final RecordServiceClient           recordClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TemplateRenderer              renderer;
    private final WebhookExecutor               webhookExecutor;

    public boolean execute(ActionDefinition action, AutomationEventContext ctx) {
        if (ctx.isDryRun()) {
            log.info("[DRY-RUN] Would execute {} with config {}", action.getActionType(), action.getConfig());
            return true;
        }

        try {
            switch (action.getActionType()) {
                case "SEND_NOTIFICATION" -> sendNotification(action.getConfig(), ctx);
                case "SEND_EMAIL"        -> sendEmail(action.getConfig(), ctx);
                case "UPDATE_FIELD"      -> updateField(action.getConfig(), ctx);
                case "UPDATE_STATUS"     -> updateStatus(action.getConfig(), ctx);
                case "PUBLISH_RECORD"    -> updateStatusDirect("PUBLISHED", ctx);
                case "ARCHIVE_RECORD"    -> updateStatusDirect("ARCHIVED", ctx);
                case "CALL_WEBHOOK"      -> callWebhook(action.getConfig(), ctx);
                case "CREATE_RECORD"     -> createRecord(action.getConfig(), ctx);
                case "LOG_ACTIVITY"         -> logActivity(action.getConfig(), ctx);
                case "EXPIRE_VERIFICATION"  -> expireVerification(action.getConfig(), ctx);
                case "REVOKE_VERIFICATION"  -> revokeVerification(action.getConfig(), ctx);
                default -> log.warn("Unknown action type: {}", action.getActionType());
            }
            return true;
        } catch (Exception e) {
            log.error("Action {} failed: {}", action.getActionType(), e.getMessage(), e);
            return false;
        }
    }

    // ── action implementations ────────────────────────────────────────────────

    private void sendNotification(Map<String, Object> config, AutomationEventContext ctx) {
        publishActionEvent("SEND_NOTIFICATION", renderer.renderMap(config, ctx), null, ctx);
        log.debug("Published ACTION_SUCCEEDED/SEND_NOTIFICATION for record {}", ctx.getRecordId());
    }

    private void sendEmail(Map<String, Object> config, AutomationEventContext ctx) {
        Map<String, Object> payload = new HashMap<>(renderer.renderMap(config, ctx));
        payload.put("channel", "EMAIL");
        publishActionEvent("SEND_NOTIFICATION", payload, null, ctx);
    }

    private void updateField(Map<String, Object> config, AutomationEventContext ctx) {
        String field = (String) config.get("field");
        Object value = config.get("value");
        if (field == null || ctx.getRecordId() == null) return;

        String resolvedValue = renderer.render(String.valueOf(value), ctx);
        // field may be "data.fieldName" — strip "data." prefix for the patch body
        String dataField = field.startsWith("data.") ? field.substring(5) : field;
        recordClient.updateRecord(
                ctx.getRecordId().toString(),
                ctx.getOrgId().toString(),
                Map.of(dataField, resolvedValue));
    }

    private void updateStatus(Map<String, Object> config, AutomationEventContext ctx) {
        String triggerName = (String) config.get("triggerName");
        String comment     = renderer.render((String) config.getOrDefault("comment", "Automated transition"), ctx);
        if (triggerName == null || ctx.getRecordId() == null) return;

        recordClient.requestStatusTransition(
                ctx.getRecordId().toString(),
                ctx.getOrgId().toString(),
                triggerName, comment);
    }

    private void updateStatusDirect(String targetTrigger, AutomationEventContext ctx) {
        if (ctx.getRecordId() == null) return;
        recordClient.requestStatusTransition(
                ctx.getRecordId().toString(),
                ctx.getOrgId().toString(),
                "to_" + targetTrigger.toLowerCase(), "Automated: " + targetTrigger);
    }

    private void callWebhook(Map<String, Object> config, AutomationEventContext ctx) {
        String url    = renderer.render((String) config.get("url"), ctx);
        String method = (String) config.getOrDefault("method", "POST");
        int    timeoutSec = (int) config.getOrDefault("timeoutSeconds", 10);

        @SuppressWarnings("unchecked")
        Map<String, Object> body    = renderer.renderMap((Map<String, Object>) config.get("body"), ctx);
        @SuppressWarnings("unchecked")
        Map<String, String> rawHeaders = (Map<String, String>) config.get("headers");

        Map<String, Object> resolvedHeaders = null;
        if (rawHeaders != null) {
            resolvedHeaders = new HashMap<>();
            for (Map.Entry<String, String> entry : rawHeaders.entrySet()) {
                resolvedHeaders.put(entry.getKey(), renderer.render(entry.getValue(), ctx));
            }
        }

        webhookExecutor.execute(url, method, body, resolvedHeaders, timeoutSec);
    }

    private void createRecord(Map<String, Object> config, AutomationEventContext ctx) {
        String objectType = renderer.render((String) config.get("objectType"), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = renderer.renderMap((Map<String, Object>) config.get("data"), ctx);

        recordClient.createRecord(ctx.getOrgId().toString(), objectType, data);
    }

    private void logActivity(Map<String, Object> config, AutomationEventContext ctx) {
        String message = renderer.render((String) config.getOrDefault("message", "Automation executed"), ctx);
        log.info("[ACTIVITY] org={} record={} event={}: {}",
                ctx.getOrgId(), ctx.getRecordId(), ctx.getEventType(), message);
        publishActionEvent("LOG_ACTIVITY", Map.of("message", message), null, ctx);
    }

    private void expireVerification(Map<String, Object> config, AutomationEventContext ctx) {
        // Bulk-expire all overdue verifications (scheduled automation use-case)
        recordClient.expireOverdueVerifications();
        publishActionEvent("EXPIRE_VERIFICATION", Map.of(), null, ctx);
    }

    private void revokeVerification(Map<String, Object> config, AutomationEventContext ctx) {
        String recordId = ctx.getRecordId() != null ? ctx.getRecordId().toString()
                : (String) config.get("recordId");
        if (recordId == null) { log.warn("REVOKE_VERIFICATION: no recordId in context or config"); return; }
        String notes = renderer.render((String) config.getOrDefault("notes", "Automated revocation"), ctx);
        recordClient.revokeVerification(recordId, notes);
        publishActionEvent("REVOKE_VERIFICATION", Map.of("recordId", recordId), null, ctx);
    }

    private void publishActionEvent(String actionType, Map<String, Object> payload,
                                    String errorMessage, AutomationEventContext ctx) {
        boolean success = errorMessage == null;
        AutomationEvent event = AutomationEvent.builder()
                .eventType(success ? "ACTION_SUCCEEDED" : "ACTION_FAILED")
                .orgId(ctx.getOrgId())
                .triggerId(ctx.getTriggerId())
                .triggerName(ctx.getTriggerName())
                .recordId(ctx.getRecordId())
                .objectType(ctx.getObjectType())
                .actionType(actionType)
                .success(success)
                .errorMessage(errorMessage)
                .payload(payload)
                .occurredAt(Instant.now())
                .build();
        String key = ctx.getOrgId() != null
                ? (ctx.getRecordId() != null ? ctx.getOrgId() + ":" + ctx.getRecordId() : ctx.getOrgId().toString())
                : "platform";
        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, key, event);
    }
}
