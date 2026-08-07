package com.lagu.platform.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A recipient's override for one notification category. Absence of a row means the platform
 * default applies — see NotificationPreferenceService.
 */
@Entity
@Table(name = "user_notification_preference", schema = "notification")
@Getter
@Setter
public class UserNotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationCategory category;

    @Column(name = "in_app", nullable = false)
    private boolean inApp = true;

    @Column(nullable = false)
    private boolean email = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
