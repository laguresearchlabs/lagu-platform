package com.lagu.platform.notification.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.notification.domain.Notification;
import com.lagu.platform.notification.domain.NotificationRepository;
import com.lagu.platform.notification.dto.NotificationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationQueryService {

    private final NotificationRepository repo;

    public Page<NotificationDto> listForUser(UUID userId, Boolean unreadOnly, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<Notification> results = Boolean.TRUE.equals(unreadOnly)
                ? repo.findByRecipientUserIdAndReadOrderByCreatedAtDesc(userId, false, pageable)
                : repo.findByRecipientUserIdOrderByCreatedAtDesc(userId, pageable);
        return results.map(this::toDto);
    }

    public long countUnread(UUID userId) {
        return repo.countByRecipientUserIdAndRead(userId, false);
    }

    public Page<NotificationDto> listForAdmin(UUID tenantId, String channel, Boolean read, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return repo.searchAdmin(tenantId, channel, read, pageable).map(this::toDto);
    }

    @Transactional
    public NotificationDto markRead(UUID id, UUID userId) {
        Notification n = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id.toString()));
        if (!userId.equals(n.getRecipientUserId())) {
            throw new ResourceNotFoundException("Notification", id.toString());
        }
        if (!n.isRead()) {
            n.setRead(true);
            n.setReadAt(Instant.now());
            repo.save(n);
        }
        return toDto(n);
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repo.markAllReadForUser(userId);
    }

    private NotificationDto toDto(Notification n) {
        return NotificationDto.builder()
                .id(n.getId())
                .tenantId(n.getTenantId())
                .recipientUserId(n.getRecipientUserId())
                .title(n.getTitle())
                .message(n.getMessage())
                .channel(n.getChannel())
                .recordId(n.getRecordId())
                .objectType(n.getObjectType())
                .triggerId(n.getTriggerId())
                .triggerName(n.getTriggerName())
                .read(n.isRead())
                .readAt(n.getReadAt() != null ? n.getReadAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .emailSent(n.isEmailSent())
                .emailSentAt(n.getEmailSentAt() != null ? n.getEmailSentAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .createdAt(n.getCreatedAt() != null ? n.getCreatedAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .build();
    }
}
