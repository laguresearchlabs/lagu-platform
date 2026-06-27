package com.lagu.platform.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class NotificationDto {
    private UUID    id;
    private UUID    orgId;
    private UUID    recipientUserId;
    private String  title;
    private String  message;
    private String  channel;
    private UUID    recordId;
    private String  objectType;
    private String  triggerName;
    private boolean read;
    private Instant readAt;
    private Instant createdAt;
}
