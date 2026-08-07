package com.lagu.platform.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserNotificationPreferenceRepository
        extends JpaRepository<UserNotificationPreference, UUID> {

    List<UserNotificationPreference> findByUserId(UUID userId);

    Optional<UserNotificationPreference> findByUserIdAndCategory(UUID userId, NotificationCategory category);
}
