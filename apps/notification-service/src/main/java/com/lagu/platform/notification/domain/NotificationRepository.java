package com.lagu.platform.notification.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndReadOrderByCreatedAtDesc(UUID userId, boolean read, Pageable pageable);

    long countByRecipientUserIdAndRead(UUID userId, boolean read);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true, n.readAt = CURRENT_TIMESTAMP WHERE n.recipientUserId = :userId AND n.read = false")
    int markAllReadForUser(UUID userId);
}
