package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class CommentResponse {
    private UUID id;
    private UUID authorUserId;
    private String content;
    private OffsetDateTime createdAt;
}
