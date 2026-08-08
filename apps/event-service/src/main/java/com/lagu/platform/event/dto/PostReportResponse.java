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

    /**
     * The reported post itself, resolved on the moderation listing only.
     *
     * <p>Null on the create-report response, where the reporter is looking at the post already.
     * A moderator is not: the reported post may sit on a feed page their client never loaded, and
     * before these were populated the queue could only offer a truncated post id, which is not
     * something anyone can moderate on. See EventPostService.listReportedPosts.
     */
    private String postContent;
    private UUID postAuthorUserId;
}
