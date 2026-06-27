package com.lagu.platform.notification.service;

import com.lagu.platform.events.AutomationEvent;
import com.lagu.platform.notification.domain.Notification;
import com.lagu.platform.notification.domain.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryService {

    private final NotificationRepository repo;
    private final EmailDeliveryService   emailService;

    /**
     * Processes an ACTION_SUCCEEDED event with actionType=SEND_NOTIFICATION.
     * The payload is expected to contain:
     *   title            — notification title (required)
     *   message          — notification body
     *   recipientUserId  — UUID of the recipient; null = not stored as in-app
     *   recipientEmail   — email address to send to (for EMAIL/BOTH channels)
     *   channel          — IN_APP (default) | EMAIL | BOTH
     *   subject          — email subject (falls back to title)
     */
    @Transactional
    public void deliver(AutomationEvent event) {
        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            log.warn("AutomationEvent {} has no payload — skipping delivery", event.getTriggerId());
            return;
        }

        String title   = str(payload, "title", "Platform Notification");
        String message = str(payload, "message", "");
        String channel = str(payload, "channel", "IN_APP").toUpperCase();

        String recipientUserIdStr = str(payload, "recipientUserId", null);
        UUID recipientUserId = recipientUserIdStr != null ? parseUuid(recipientUserIdStr) : null;

        // Store in-app notification when channel is IN_APP or BOTH
        Notification saved = null;
        if ("IN_APP".equals(channel) || "BOTH".equals(channel)) {
            saved = storeInApp(event, title, message, channel, recipientUserId);
        }

        // Send email when channel is EMAIL or BOTH
        if ("EMAIL".equals(channel) || "BOTH".equals(channel)) {
            String recipientEmail = str(payload, "recipientEmail", null);
            String subject        = str(payload, "subject", title);
            boolean sent = emailService.send(recipientEmail, subject, message);
            if (sent && saved != null) {
                saved.setEmailSent(true);
                saved.setEmailSentAt(Instant.now());
                repo.save(saved);
            } else if (sent && saved == null) {
                // EMAIL-only channel — still record for audit
                Notification emailRecord = buildNotification(event, title, message, "EMAIL", recipientUserId);
                emailRecord.setEmailSent(true);
                emailRecord.setEmailSentAt(Instant.now());
                repo.save(emailRecord);
            }
        }
    }

    private Notification storeInApp(AutomationEvent event, String title, String message,
                                     String channel, UUID recipientUserId) {
        Notification n = buildNotification(event, title, message, channel, recipientUserId);
        return repo.save(n);
    }

    private Notification buildNotification(AutomationEvent event, String title, String message,
                                            String channel, UUID recipientUserId) {
        Notification n = new Notification();
        n.setOrgId(event.getOrgId());
        n.setRecipientUserId(recipientUserId);
        n.setTitle(title);
        n.setMessage(message);
        n.setChannel(channel);
        n.setRecordId(event.getRecordId());
        n.setObjectType(event.getObjectType());
        n.setTriggerId(event.getTriggerId());
        n.setTriggerName(event.getTriggerName());
        return n;
    }

    private String str(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : defaultVal;
    }

    private UUID parseUuid(String s) {
        try { return UUID.fromString(s); }
        catch (Exception e) { return null; }
    }
}
