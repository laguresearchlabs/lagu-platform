package com.lagu.platform.notification.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    java.util.Optional<Notification> findBySourceEventId(UUID sourceEventId);

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndReadOrderByCreatedAtDesc(UUID userId, boolean read, Pageable pageable);

    long countByRecipientUserIdAndRead(UUID userId, boolean read);

    @Query("SELECT n FROM Notification n WHERE " +
           "(:tenantId IS NULL OR n.tenantId = :tenantId) AND " +
           "(:channel IS NULL OR n.channel = :channel) AND " +
           "(:read IS NULL OR n.read = :read) " +
           "ORDER BY n.createdAt DESC")
    Page<Notification> searchAdmin(@Param("tenantId") UUID tenantId,
                                    @Param("channel") String channel,
                                    @Param("read") Boolean read,
                                    Pageable pageable);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true, n.readAt = CURRENT_TIMESTAMP WHERE n.recipientUserId = :userId AND n.read = false")
    int markAllReadForUser(UUID userId);
}
