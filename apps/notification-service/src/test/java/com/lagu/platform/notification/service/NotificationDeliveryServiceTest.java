package com.lagu.platform.notification.service;

import com.lagu.platform.events.AutomationEvent;
import com.lagu.platform.notification.domain.Notification;
import com.lagu.platform.notification.domain.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the review's finding: NotificationDeliveryService had no idempotency
 * key at all, so any Kafka redelivery of the same AutomationEvent (the relay is explicitly
 * at-least-once) created a second Notification row and re-sent the email. These tests pin the
 * fix built on AutomationEvent.eventId + Notification.sourceEventId.
 */
class NotificationDeliveryServiceTest {

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final NotificationPersistenceService persistence = mock(NotificationPersistenceService.class);
    private final EmailDeliveryService emailService = mock(EmailDeliveryService.class);

    private final NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);

    private final NotificationDeliveryService service =
            new NotificationDeliveryService(repo, persistence, emailService, preferences);

    /** Default stance for the pre-existing idempotency tests: nothing is suppressed. */
    @BeforeEach
    void allowEverything() {
        when(preferences.effective(any(), any()))
                .thenReturn(new NotificationPreferenceService.Setting(true, true));
    }

    private static AutomationEvent event(UUID eventId, Map<String, Object> payload) {
        return AutomationEvent.builder()
                .eventId(eventId)
                .eventType("ACTION_SUCCEEDED")
                .actionType("SEND_NOTIFICATION")
                .tenantId(UUID.randomUUID())
                .triggerId(UUID.randomUUID())
                .recordId(UUID.randomUUID())
                .payload(payload)
                .build();
    }

    private static Notification savedWith(UUID id, boolean emailSent) {
        Notification n = new Notification();
        n.setId(id);
        n.setEmailSent(emailSent);
        return n;
    }

    @Test
    void noPayloadSkipsEntirelyWithNoSideEffects() {
        service.deliver(event(UUID.randomUUID(), null));

        verifyNoInteractions(persistence, emailService);
    }

    @Test
    void inAppChannelPersistsOnceAndNeverCallsEmail() {
        UUID eventId = UUID.randomUUID();
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.empty());
        when(persistence.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deliver(event(eventId, Map.of("title", "Hi", "channel", "IN_APP")));

        verify(persistence).save(argThat(n -> eventId.equals(n.getSourceEventId())));
        verifyNoInteractions(emailService);
    }

    @Test
    void emailOnlyChannelOnSuccessPersistsAnAlreadyMarkedAuditRow() {
        // EMAIL-only never goes through persistence.save()-then-markEmailResult() — it's saved
        // once, already marked sent, since NotificationQueryService's in-app feed query doesn't
        // filter by channel and an unconditional early row would otherwise leak an EMAIL-only
        // notification into it.
        UUID eventId = UUID.randomUUID();
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.empty());
        when(persistence.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(emailService.send(eq("a@b.com"), any(), any())).thenReturn(true);

        service.deliver(event(eventId, Map.of(
                "title", "Hi", "channel", "EMAIL", "recipientEmail", "a@b.com")));

        verify(persistence).save(argThat(n -> n.isEmailSent() && eventId.equals(n.getSourceEventId())));
        verify(persistence, never()).markEmailResult(any(), anyBoolean());
    }

    @Test
    void emailOnlyChannelOnFailureCreatesNoRowAtAll() {
        UUID eventId = UUID.randomUUID();
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.empty());
        when(emailService.send(any(), any(), any())).thenReturn(false);

        service.deliver(event(eventId, Map.of(
                "title", "Hi", "channel", "EMAIL", "recipientEmail", "a@b.com")));

        verify(persistence, never()).save(any());
        verify(persistence, never()).markEmailResult(any(), anyBoolean());
    }

    @Test
    void bothChannelPersistsThenSendsThenMarksResult() {
        UUID eventId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.empty());
        when(persistence.save(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(rowId);
            return n;
        });
        when(emailService.send(any(), any(), any())).thenReturn(true);

        service.deliver(event(eventId, Map.of(
                "title", "Hi", "channel", "BOTH", "recipientEmail", "a@b.com")));

        verify(persistence).save(any());
        verify(emailService).send(eq("a@b.com"), any(), any());
        verify(persistence).markEmailResult(rowId, true);
    }

    @Test
    void redeliveryOfFullyDeliveredEventIsSkippedEntirely() {
        UUID eventId = UUID.randomUUID();
        Notification existing = savedWith(UUID.randomUUID(), true); // already emailSent=true
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.of(existing));

        service.deliver(event(eventId, Map.of(
                "title", "Hi", "channel", "EMAIL", "recipientEmail", "a@b.com")));

        verifyNoInteractions(emailService);
        verify(persistence, never()).save(any());
        verify(persistence, never()).markEmailResult(any(), anyBoolean());
    }

    @Test
    void redeliveryAfterPartialFailureResumesTheEmailStepOnly() {
        // Row exists (from a prior attempt) but its email was never actually sent yet — the
        // retry must send the email and update the *existing* row, not create a second one.
        UUID eventId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        Notification existing = savedWith(rowId, false);
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.of(existing));
        when(emailService.send(any(), any(), any())).thenReturn(true);

        service.deliver(event(eventId, Map.of(
                "title", "Hi", "channel", "EMAIL", "recipientEmail", "a@b.com")));

        verify(persistence, never()).save(any()); // no duplicate row
        verify(emailService).send(eq("a@b.com"), any(), any());
        verify(persistence).markEmailResult(rowId, true);
    }

    @Test
    void inAppOnlyEventWithNoSourceEventIdAlwaysPersists() {
        // Some callers may not have an eventId (defensive: builder default should prevent this
        // in practice, but the null-safe branch must not skip persistence outright).
        when(persistence.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AutomationEvent noId = AutomationEvent.builder()
                .eventId(null).eventType("ACTION_SUCCEEDED").actionType("SEND_NOTIFICATION")
                .tenantId(UUID.randomUUID())
                .payload(Map.of("title", "Hi", "channel", "IN_APP"))
                .build();

        service.deliver(noId);

        verify(persistence).save(any());
        verify(repo, never()).findBySourceEventId(any());
    }

    // ── Recipient preferences ─────────────────────────────────────────────────

    @Test
    void suppressedCategoryCreatesNoRowAtAll() {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        when(preferences.effective(eq(recipient), eq(com.lagu.platform.notification.domain.NotificationCategory.MARKETING)))
                .thenReturn(new NotificationPreferenceService.Setting(false, false));

        service.deliver(event(eventId, Map.of(
                "title", "News", "channel", "BOTH",
                "category", "MARKETING",
                "recipientUserId", recipient.toString())));

        // Not merely "no email" — a suppressed notification must leave no audit artefact and
        // must not appear in the recipient's in-app feed either.
        verifyNoInteractions(persistence, emailService);
        verify(repo, never()).findBySourceEventId(any());
    }

    @Test
    void inAppStillPersistsWhenOnlyEmailIsSuppressed() {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.empty());
        when(persistence.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(preferences.effective(eq(recipient), any()))
                .thenReturn(new NotificationPreferenceService.Setting(true, false));

        service.deliver(event(eventId, Map.of(
                "title", "Reminder", "channel", "BOTH",
                "category", "EVENT_REMINDERS",
                "recipientUserId", recipient.toString())));

        verify(persistence).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void notificationWithNoCategoryIsTreatedAsTransactionalAndDelivered() {
        UUID eventId = UUID.randomUUID();
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.empty());
        when(persistence.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Every automation predating this feature sends no category. They must keep working.
        service.deliver(event(eventId, Map.of("title", "Hi", "channel", "IN_APP")));

        verify(preferences).effective(any(),
                eq(com.lagu.platform.notification.domain.NotificationCategory.TRANSACTIONAL));
        verify(persistence).save(any());
    }

    @Test
    void unknownCategoryFallsBackToDeliveringRatherThanDropping() {
        UUID eventId = UUID.randomUUID();
        when(repo.findBySourceEventId(eventId)).thenReturn(Optional.empty());
        when(persistence.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deliver(event(eventId, Map.of(
                "title", "Hi", "channel", "IN_APP", "category", "NOT_A_REAL_CATEGORY")));

        verify(preferences).effective(any(),
                eq(com.lagu.platform.notification.domain.NotificationCategory.TRANSACTIONAL));
        verify(persistence).save(any());
    }
}
