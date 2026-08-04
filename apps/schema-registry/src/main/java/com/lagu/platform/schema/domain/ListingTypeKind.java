package com.lagu.platform.schema.domain;

/**
 * What a listing type fundamentally is, so consumers can select types without parsing names.
 *
 * <p>This replaces the {@code name.endsWith("_EVENT")} convention events-ui used to discover event
 * types — a convention that silently drops any admin-authored type not following it, which is
 * exactly the case the no-code platform exists to support.
 *
 * <p>Distinct from {@code CategoryDefinition}, which classifies individual listings *within* a
 * type (cuisine, venue style); this classifies the type itself.
 */
public enum ListingTypeKind {

    /** A marketplace listing offered by a vendor: VENUE, PHOTOGRAPHER, CATERER, VENDOR, … */
    LISTING,

    /** A planned event owned by a consumer: WEDDING_EVENT, BIRTHDAY_EVENT, CORPORATE_EVENT, … */
    EVENT,

    /** A sub-object of an event's social feed: EVENT_POST, EVENT_COMMENT, EVENT_POST_REPORT. */
    SOCIAL
}
