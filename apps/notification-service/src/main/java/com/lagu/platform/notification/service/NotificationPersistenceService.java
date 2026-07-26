package com.lagu.platform.notification.service;

import com.lagu.platform.notification.domain.Notification;
import com.lagu.platform.notification.domain.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Split out of NotificationDeliveryService so the DB writes around
 * {@link EmailDeliveryService#send} are each their own transaction, called through a real proxy
 * (not self-invocation, which would silently no-op the {@code @Transactional} the way it did in
 * automation-service's AutomationExecutor — see AutomationRunService for that fix). Wrapping the
 * email send itself in the same transaction as these writes would mean a DB failure after a
 * successful send rolls back the "already sent" record, and the Kafka retry that follows would
 * send the email again — an irreversible external action must never sit inside a transaction
 * whose rollback can't undo it.
 */
@Service
@RequiredArgsConstructor
public class NotificationPersistenceService {

    private final NotificationRepository repo;

    @Transactional
    public Notification save(Notification n) {
        return repo.save(n);
    }

    @Transactional
    public void markEmailResult(UUID notificationId, boolean sent) {
        repo.findById(notificationId).ifPresent(n -> {
            n.setEmailSent(sent);
            n.setEmailSentAt(sent ? Instant.now() : null);
            repo.save(n);
        });
    }
}
