package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class JoinRequestResponse {
    private UUID id;
    private UUID userId;
    private String requestedRole;
    private String status;
    private String message;
    private UUID reviewedByUserId;
    private Instant reviewedAt;
    private Instant createdAt;
}
