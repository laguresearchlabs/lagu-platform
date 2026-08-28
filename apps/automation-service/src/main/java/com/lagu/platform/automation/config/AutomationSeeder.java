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
 * vendor about their own action instead of the consumer).
 *
 * <p>Vendor-side booking notifications are seeded too, taking the first of the two routes the
 * earlier version of this comment sketched: booking-service resolves the org's owner and puts it
 * on the event as {@code vendorRecipientUserId}, so automation-service still needs no
 * vendor-service integration and notification-service still delivers to one recipient. Both
 * vendor-side conditions ride on fields booking-service computes, because neither is expressible
 * here — ConditionEvaluator compares a field to a constant, never to another field:
 *
 * <ul>
 *   <li>{@code data.vendorRecipientUserId IS_NOT_NULL} — the lookup is best-effort, and a trigger
 *       that fired without it would address a notification to nobody.</li>
 *   <li>{@code data.actorSide EQ CONSUMER} — cancel and complete are open to both parties, so
 *       without this a vendor cancelling would be told that their own booking was cancelled.</li>
 * </ul>
 *
 * <p><b>Known limit:</b> only the owner is notified. A vendor org can have several ADMIN/MEMBER
 * users handling bookings and none of them hear anything, because notification-service takes a
 * single recipientUserId and has no fan-out. Widening this means fan-out there, not a change
 * here — the event already names the org.
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
        seedVendorBookingNotifications();
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

    /**
     * The vendor half of the booking lifecycle. QUOTED is absent on purpose — the vendor is the
     * one who quotes, and telling them what they just did is noise.
     */
    private void seedVendorBookingNotifications() {
        seedVendorBookingNotification("booking_inquired_vendor_notification", "New Inquiry — Vendor", "INQUIRED",
                "New booking inquiry",
                "A customer is asking about your listing for {{data.eventDate}}. "
                        + "They see nothing until you send a quote.");
        seedVendorBookingNotification("booking_confirmed_vendor_notification", "Booking Confirmed — Vendor", "CONFIRMED",
                "Your quote was accepted",
                "The customer confirmed the booking for {{data.eventDate}}. The date is now held on your calendar.");
        seedVendorBookingNotification("booking_cancelled_vendor_notification", "Booking Cancelled — Vendor", "CANCELLED",
                "Booking cancelled by the customer",
                "The booking for {{data.eventDate}} was cancelled and the date is free again.");
        seedVendorBookingNotification("booking_completed_vendor_notification", "Booking Completed — Vendor", "COMPLETED",
                "Booking marked complete",
                "The customer marked the booking for {{data.eventDate}} as complete.");
    }

    private void seedVendorBookingNotification(String name, String label, String eventType,
                                               String title, String message) {
        if (triggerRepo.findByNameAndTenantIdIsNull(name).isPresent()) return;

        TriggerDefinition trigger = newTrigger(name, label, eventType, null);
        trigger.setConditions(List.of(
                Map.of("field", "data.vendorRecipientUserId", "operator", "IS_NOT_NULL"),
                Map.of("field", "data.actorSide", "operator", "EQ", "value", "CONSUMER")));
        // TRANSACTIONAL for the same reason as the consumer side: a vendor must not be able to
        // switch off being told that a customer is waiting on them.
        ActionDefinition action = sendNotificationAction(trigger, title, message,
                "{{data.vendorRecipientUserId}}", "TRANSACTIONAL");

        // BOTH rather than the IN_APP default. A vendor who is not currently in the portal is
        // exactly the vendor who needs telling, and an in-app row they never log in to see is
        // indistinguishable from no notification at all.
        //
        // The address is best-effort: it is optional on the VENDOR schema, and a blank one makes
        // NotificationDeliveryService log and skip the email half while still writing the in-app
        // row — so this degrades to the old behaviour rather than failing. That is also why there
        // is no IS_NOT_NULL condition on the email the way there is on the recipient id: no
        // address is a reason to send less, not a reason not to fire.
        action.getConfig().put("channel", "BOTH");
        action.getConfig().put("recipientEmail", "{{data.vendorRecipientEmail}}");
        action.getConfig().put("subject", title);

        trigger.setActions(List.of(action));
        triggerRepo.save(trigger);
        log.info("Seeded trigger: {}", name);
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
