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
    /**
     * The post's photos, each with a freshly signed URL. Replaces the image-service id list:
     * clients render these directly rather than resolving ids against another service.
     *
     * <p>URLs are short-lived and minted per response — never cache or persist them.
     */
    private List<PostImage> images;
    private boolean pinned;
    private boolean locked;
    private String status;
    private long likeCount;
    private boolean likedByMe;
    private OffsetDateTime createdAt;

    /** One photo on a post. Mirrors the gallery item shape used everywhere else in the platform. */
    @Data
    @Builder
    public static class PostImage {
        private UUID id;
        private String url;
        private String thumbnailUrl;
        private String caption;
    }
}
