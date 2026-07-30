package com.lagu.platform.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class NotificationDto {
    private UUID    id;
    private UUID    tenantId;
    private UUID    recipientUserId;
    private String  title;
    private String  message;
    private String  channel;
    private UUID    recordId;
    private String  objectType;
    private UUID    triggerId;
    private String  triggerName;
    private boolean read;
    private OffsetDateTime readAt;
    private boolean emailSent;
    private OffsetDateTime emailSentAt;
    private OffsetDateTime createdAt;
}
