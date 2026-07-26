package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class PostReportResponse {
    private UUID id;
    private UUID postId;
    private UUID reporterUserId;
    private String reason;
    private String details;
    private OffsetDateTime createdAt;
}
