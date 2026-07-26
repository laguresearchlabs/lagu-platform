package com.lagu.platform.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PostResponse {
    private UUID id;
    private UUID authorUserId;
    private String content;
    private List<UUID> imageIds;
    private boolean pinned;
    private boolean locked;
    private String status;
    private long likeCount;
    private boolean likedByMe;
    private OffsetDateTime createdAt;
}
