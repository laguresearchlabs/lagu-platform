package com.lagu.platform.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification", schema = "notification")
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "recipient_user_id")
    private UUID recipientUserId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    /** IN_APP | EMAIL | BOTH */
    @Column(nullable = false, length = 20)
    private String channel = "IN_APP";

    @Column(name = "record_id")
    private UUID recordId;

    @Column(name = "object_type", length = 100)
    private String objectType;

    @Column(name = "trigger_id")
    private UUID triggerId;

    @Column(name = "trigger_name")
    private String triggerName;

    /** Dedup key — the originating AutomationEvent's eventId. Null for notifications created
     *  directly via the REST API rather than by an automation. See NotificationDeliveryService. */
    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "email_sent", nullable = false)
    private boolean emailSent = false;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
