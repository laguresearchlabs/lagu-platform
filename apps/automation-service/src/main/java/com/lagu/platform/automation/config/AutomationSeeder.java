package com.lagu.platform.automation.config;

import com.lagu.platform.automation.domain.ActionDefinition;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.domain.TriggerDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Platform-wide (tenant_id IS NULL, matches every org per TriggerDefinitionRepository's queries —
 * necessary since event-service mints a fresh throwaway org per event, so there is no single
 * "the event org" to scope a seeded row to) notification triggers for BIRTHDAY_EVENT/
 * WEDDING_EVENT. Replaces event-nest's NotificationService, which wrote directly to its own
 * `notifications` table on every event/member/address mutation.
 *
 * Scope note: only RECORD_CREATED/RECORD_STATUS_CHANGED on the event record itself are wired
 * here. Membership changes (invite/remove/role change) are NOT record-service events at all —
 * EventMember is event-service's own local table, never touches record-service — so they are
 * structurally invisible to automation-service's Kafka-event-driven model and can't be covered
 * this way. Likewise EVENT_POST approve/reject fires with `changedBy` = the moderator who acted,
 * not the post's original author, so a "your post was approved" notification isn't expressible
 * here either (TemplateRenderer only exposes the current actor, never a record's original
 * creator) — both would need a data-model change (event-service publishing its own domain
 * events, or AutomationEventContext carrying the record's createdBy) to become possible.
 *
 * <p>Also seeds consumer-side notifications for booking-service's BOOKING_EVENTS (quoted/
 * confirmed/cancelled/completed -> notify {@code booking.consumerUserId}, via
 * {@code {{data.consumerUserId}}} rather than {@code {{changedBy}}} — booking's own actor is
 * frequently the *other* party, e.g. the vendor quotes, so notifying "changedBy" would notify the
 * vendor about their own action instead of the consumer). Vendor-side booking notifications
 * (new inquiry, consumer confirmed/cancelled) are NOT wired — "notify the vendor" isn't a single
 * userId the way "notify the consumer" is, since a vendor org can have multiple VendorMembers,
 * and automation-service has no vendor-service integration to resolve which member(s) to notify.
 * That needs either booking-service resolving a specific vendor user (a new vendor-service call
 * it doesn't make today) or a "notify all active org members" fan-out that doesn't exist
 * anywhere in notification-service (it only takes one recipientUserId) — neither is a small
 * addition, so left undone rather than guessed at.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationSeeder implements ApplicationRunner {

    private final TriggerDefinitionRepository triggerRepo;

    @Value("${platform.seeder.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        log.info("Running AutomationSeeder...");
        for (String objectType : List.of("BIRTHDAY_EVENT", "WEDDING_EVENT")) {
            seedCreatedNotification(objectType);
            seedStatusChangedNotification(objectType);
        }
        seedBookingNotifications();
        log.info("AutomationSeeder complete");
    }

    private void seedBookingNotifications() {
        seedBookingNotification("booking_quoted_notification", "Booking Quoted", "QUOTED",
                "You've received a quote",
                "The vendor sent a price quote for your booking inquiry — review and confirm in the app.");
        seedBookingNotification("booking_confirmed_notification", "Booking Confirmed", "CONFIRMED",
                "Booking confirmed",
                "Your booking is confirmed for {{data.eventDate}}.");
        seedBookingNotification("booking_cancelled_notification", "Booking Cancelled", "CANCELLED",
                "Booking cancelled",
                "This booking has been cancelled.");
        seedBookingNotification("booking_completed_notification", "Booking Completed", "COMPLETED",
                "Booking complete",
                "Your booking is complete — we hope it went great!");
    }

    private void seedBookingNotification(String name, String label, String eventType,
                                         String title, String message) {
        if (triggerRepo.findByNameAndTenantIdIsNull(name).isPresent()) return;

        TriggerDefinition trigger = newTrigger(name, label, eventType, null);
        // Booking lifecycle mail is transactional — a consumer must not be able to opt out of
        // being told their booking was confirmed or cancelled.
        trigger.setActions(List.of(sendNotificationAction(trigger, title, message,
                "{{data.consumerUserId}}", "TRANSACTIONAL")));
        triggerRepo.save(trigger);
        log.info("Seeded trigger: {}", name);
    }

    private void seedCreatedNotification(String objectType) {
        String name = "event_created_notification_" + objectType.toLowerCase();
        if (triggerRepo.findByNameAndTenantIdIsNull(name).isPresent()) return;

        TriggerDefinition trigger = newTrigger(name, "Event Created — " + objectType,
                "RECORD_CREATED", objectType);
        trigger.setActions(List.of(sendNotificationAction(trigger,
                "Event Created", "Your event has been created and is ready for planning.",
                "EVENT_UPDATES")));
        triggerRepo.save(trigger);
        log.info("Seeded trigger: {}", name);
    }

    private void seedStatusChangedNotification(String objectType) {
        String name = "event_status_changed_notification_" + objectType.toLowerCase();
        if (triggerRepo.findByNameAndTenantIdIsNull(name).isPresent()) return;

        TriggerDefinition trigger = newTrigger(name, "Event Status Changed — " + objectType,
                "RECORD_STATUS_CHANGED", objectType);
        trigger.setActions(List.of(sendNotificationAction(trigger,
                "Event Status Updated", "Your event status changed to {{currentStatus}}.",
                "EVENT_UPDATES")));
        triggerRepo.save(trigger);
        log.info("Seeded trigger: {}", name);
    }

    private TriggerDefinition newTrigger(String name, String label, String eventType, String objectType) {
        TriggerDefinition trigger = new TriggerDefinition();
        trigger.setTenantId(null); // platform-wide — matches every org via (t.tenantId = :tenantId OR t.tenantId IS NULL)
        trigger.setName(name);
        trigger.setLabel(label);
        trigger.setEventType(eventType);
        trigger.setObjectType(objectType);
        trigger.setActive(true);
        return trigger;
    }

    private ActionDefinition sendNotificationAction(TriggerDefinition trigger, String title, String message,
                                                    String category) {
        return sendNotificationAction(trigger, title, message, "{{changedBy}}", category);
    }

    /**
     * recipientUserId is templated separately from the default {{changedBy}} — for booking
     * triggers, the actor is frequently the *other* party (the vendor quotes; the consumer
     * should be notified, not the vendor who just acted).
     */
    private ActionDefinition sendNotificationAction(TriggerDefinition trigger, String title, String message,
                                                    String recipientUserIdTemplate, String category) {
        ActionDefinition action = new ActionDefinition();
        action.setTrigger(trigger);
        action.setActionType("SEND_NOTIFICATION");
        action.setExecutionOrder(0);
        action.setActive(true);
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("title", title);
        config.put("message", message);
        config.put("channel", "IN_APP");
        config.put("recipientUserId", recipientUserIdTemplate);
        // Read by notification-service to decide whether the recipient's preferences allow this.
        // ActionExecutor forwards the whole config into the event payload, so no change is
        // needed there. See todo/19-notification-preferences.md.
        config.put("category", category);
        action.setConfig(config);
        return action;
    }
}
