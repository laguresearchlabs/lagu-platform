package com.lagu.platform.notification.service;

import com.lagu.platform.events.AutomationEvent;
import com.lagu.platform.notification.domain.Notification;
import com.lagu.platform.notification.domain.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryService {

    private final NotificationRepository         repo;
    private final NotificationPersistenceService persistence;
    private final EmailDeliveryService           emailService;

    /**
     * Processes an ACTION_SUCCEEDED event with actionType=SEND_NOTIFICATION.
     * The payload is expected to contain:
     *   title            — notification title (required)
     *   message          — notification body
     *   recipientUserId  — UUID of the recipient; null = not stored as in-app
     *   recipientEmail   — email address to send to (for EMAIL/BOTH channels)
     *   channel          — IN_APP (default) | EMAIL | BOTH
     *   subject          — email subject (falls back to title)
     *
     * Not annotated {@code @Transactional} at this level — see NotificationPersistenceService
     * for why the DB writes around the email send need to be their own separate transactions
     * rather than one spanning the whole method.
     */
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

        boolean needsInApp = "IN_APP".equals(channel) || "BOTH".equals(channel);
        boolean needsEmail = "EMAIL".equals(channel) || "BOTH".equals(channel);

        // Idempotency: a Kafka redelivery of the exact same AutomationEvent carries the same
        // eventId (see AutomationEvent.eventId), so re-entering here for it must resume rather
        // than re-create a duplicate row and re-send an already-sent email. A prior partial
        // attempt (e.g. the row saved but the process crashed before the email send) is resumed
        // from whichever step didn't complete, rather than being skipped outright or redone.
        UUID sourceEventId = event.getEventId();
        Notification saved = sourceEventId != null
                ? repo.findBySourceEventId(sourceEventId).orElse(null) : null;

        if (saved != null && (!needsEmail || saved.isEmailSent())) {
            log.info("Notification for event {} already fully delivered — skipping duplicate", sourceEventId);
            return;
        }

        if (saved == null && needsInApp) {
            Notification n = buildNotification(event, title, message, channel, recipientUserId);
            n.setSourceEventId(sourceEventId);
            saved = persistence.save(n);
        }

        if (needsEmail) {
            String recipientEmail = str(payload, "recipientEmail", null);
            String subject        = str(payload, "subject", title);
            boolean sent = emailService.send(recipientEmail, subject, message);
            if (saved != null) {
                persistence.markEmailResult(saved.getId(), sent);
            } else if (sent) {
                // EMAIL-only channel, no in-app row: only persisted on a successful send —
                // matches the pre-existing "audit record" behavior for this channel and,
                // importantly, keeps a never-sent EMAIL-only notification out of the recipient's
                // in-app feed (NotificationQueryService's query doesn't filter by channel, so an
                // unconditionally-created row here would leak into it). The one gap this leaves:
                // if the send succeeds but this save then fails, a retry has no row to find and
                // will send the email again — accepted as a narrower, rarer window than the
                // guaranteed-duplicate this fix closes for every other case.
                Notification emailRecord = buildNotification(event, title, message, "EMAIL", recipientUserId);
                emailRecord.setSourceEventId(sourceEventId);
                emailRecord.setEmailSent(true);
                emailRecord.setEmailSentAt(java.time.Instant.now());
                persistence.save(emailRecord);
            }
        }
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
