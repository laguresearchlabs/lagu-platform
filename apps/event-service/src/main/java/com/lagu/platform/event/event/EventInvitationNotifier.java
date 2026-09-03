package com.lagu.platform.event.event;

import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.event.domain.EventInvitation;
import com.lagu.platform.events.AutomationEvent;
import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Tells somebody they have been invited.
 *
 * <p>An invitation to a person with no account is worth nothing until they hear about it, and
 * this is the only thing that tells them — they have no session, no in-app inbox and no reason to
 * visit. Until this existed the feature recorded the invitation correctly and left the host to
 * pass it on by other means, which is half a feature wearing the shape of a whole one.
 *
 * <p>Staged in the outbox, not sent. {@link TransactionalOutbox#stage} must run inside the same
 * transaction that writes the invitation row, so the two commit together: the alternative is an
 * email about an invitation that rolled back, or an invitation nobody was ever told about.
 *
 * <p>No new consumer or template. notification-service already handles an {@link AutomationEvent}
 * carrying {@code ACTION_SUCCEEDED} + {@code SEND_NOTIFICATION}, with idempotency keyed on
 * {@code eventId} so a Kafka redelivery does not send a second email. This just speaks that
 * contract.
 */
@Component
@RequiredArgsConstructor
public class EventInvitationNotifier {

    /** What notification-service dispatches on — see NotificationDeliveryService.deliver. */
    private static final String ACTION_SUCCEEDED = "ACTION_SUCCEEDED";
    private static final String SEND_NOTIFICATION = "SEND_NOTIFICATION";

    private final TransactionalOutbox outbox;

    @Value("${platform.app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    /** Email-only call sites that have not resolved a recipient — see the overload below. */
    public void invitationCreated(EventInvitation invitation, String eventName) {
        invitationCreated(invitation, eventName, null);
    }

    /**
     * @param eventName     what the host called it, for a subject line worth opening. Blank for an
     *                      event with no name yet, which the copy below handles rather than
     *                      printing "You're invited to ".
     * @param recipientUserId the invitee's own account, when the address already belongs to one
     *                      (see {@link com.lagu.platform.event.client.UserServiceClient}). Null is
     *                      the ordinary case — this feature exists for people with no account yet
     *                      — and notification-service treats a null recipient as nobody whose
     *                      preferences apply, exactly as before this parameter existed.
     */
    public void invitationCreated(EventInvitation invitation, String eventName, UUID recipientUserId) {
        String name = eventName == null || eventName.isBlank() ? null : eventName.trim();
        String subject = name == null ? "You have an invitation" : "You're invited to " + name;

        AutomationEvent event = AutomationEvent.builder()
                .eventType(ACTION_SUCCEEDED)
                .actionType(SEND_NOTIFICATION)
                .success(true)
                .tenantId(invitation.getTenantId())
                .recordId(invitation.getEventId())
                .objectType("EVENT_INVITATION")
                .triggerName("event-invitation-created")
                .occurredAt(Instant.now())
                .payload(payload(invitation, name, subject, recipientUserId))
                .build();

        outbox.stage(PlatformTopics.AUTOMATION_EVENTS, invitation.getId().toString(), event);
    }

    private Map<String, Object> payload(EventInvitation invitation, String name, String subject,
                                         UUID recipientUserId) {
        String what = name == null ? "an event" : name;

        // Map.of rejects a null value outright, and recipientUserId is null on the common path
        // (nobody with an account yet) — a HashMap tolerates that without a null-check at each
        // call site.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("title", subject);
        payload.put("subject", subject);
        payload.put("message", "You've been invited to " + what + " on Lagu. Sign up with "
                + invitation.getEmail() + " and it will be waiting for you: "
                + appBaseUrl + "/auth/signup");
        // EMAIL, not BOTH: there is nobody to show an in-app notification to. The whole point of
        // this invitation is that the recipient has no account yet — except when recipientUserId
        // is set, in which case they do, and notification-service's own EVENT_INVITES preference
        // (keyed on that id) decides whether this email actually goes out.
        payload.put("channel", "EMAIL");
        payload.put("recipientEmail", invitation.getEmail());
        payload.put("recipientUserId", recipientUserId == null ? null : recipientUserId.toString());
        payload.put("category", "EVENT_INVITES");
        return payload;
    }
}
