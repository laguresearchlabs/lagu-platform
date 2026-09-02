package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * The only unauthenticated projection of an event, serving link-preview crawlers
 * (WhatsApp, Twitterbot, Facebook) that cannot log in to render an Open Graph card for
 * /share/&lt;id&gt;, and the projection the share page itself renders.
 *
 * <p>The scalars stay hand-picked, because a crawler's card needs exactly those and nothing
 * about membership or ownership may be exposed. {@code data} is the addition: the record's own
 * values, filtered to the fields of sections a listing type marked {@code audience: PUBLIC}, so
 * a shared link can render the same invitation a member sees rather than a title and a blurb.
 *
 * <p>The default is still private. A type that has marked no section PUBLIC — which is every
 * type until an admin says otherwise — yields an empty map, and so does any failure to resolve
 * the schema; see SchemaRegistryClient.publicFields, which fails closed on purpose. A field a
 * listing type adds later is private until somebody publishes it into a PUBLIC section.
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

    /**
     * The record's PUBLIC-audience values, keyed as the schema keys them. Empty rather than null
     * when there are none, so the client never has to tell "no public fields" apart from "an old
     * server that did not send any".
     */
    private Map<String, Object> data;

    /**
     * The schema version {@code data} was filtered against, so the client renders it through the
     * same snapshot rather than through whatever is live by the time the link is opened. Null
     * when no schema could be resolved, which is also when {@code data} is empty.
     */
    private Integer schemaVersion;
}
