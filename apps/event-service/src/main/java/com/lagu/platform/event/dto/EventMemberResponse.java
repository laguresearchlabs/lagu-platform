package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class EventMemberResponse {
    private UUID id;
    private UUID userId;
    private String role;
    private String status;
    private String guestNote;
    private boolean muted;
    private UUID invitedBy;
    private OffsetDateTime joinedAt;
}
