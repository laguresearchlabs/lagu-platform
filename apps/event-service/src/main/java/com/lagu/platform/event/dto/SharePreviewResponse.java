package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

/**
 * The only unauthenticated projection of an event, serving link-preview crawlers
 * (WhatsApp, Twitterbot, Facebook) that cannot log in to render an Open Graph card for
 * /share/&lt;id&gt;. Deliberately a hand-picked subset rather than the schema-driven data map:
 * whatever a listing type adds later stays private by default, and nothing about membership
 * or ownership is exposed. EventService.getSharePreview() serves this for PUBLIC events only.
 */
@Data
@Builder
public class SharePreviewResponse {
    private String objectType;
    private String title;
    private String description;
    private String coverImage;
    private String startDatetime;
    private String city;
    private String state;
}
