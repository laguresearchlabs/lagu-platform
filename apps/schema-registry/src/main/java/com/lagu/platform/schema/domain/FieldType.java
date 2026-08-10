package com.lagu.platform.schema.domain;

public enum FieldType {
    TEXT, LONG_TEXT, NUMBER, DECIMAL, BOOLEAN,
    DATE, DATETIME, TIME,
    EMAIL, PHONE, URL,
    ADDRESS, GEOLOCATION, CURRENCY,
    ENUM, MULTI_SELECT,
    FILE, IMAGE,
    /**
     * An ordered set of photographs with captions and a cover shot, stored as an array of
     * {@code {id, key, caption, isPrimary}} objects.
     *
     * <p>Distinct from a repeated IMAGE because a gallery's items are added, captioned,
     * reordered and removed one at a time, and from ARRAY_OF_OBJECTS because its contents are
     * written only by record-service's upload flow — never by a client. How many photos it
     * accepts, and of what kind, comes from {@code validationRules}
     * ({@code minCount}, {@code maxCount}, {@code allowedMimeTypes}, {@code maxSizeMb}).
     */
    MEDIA_GALLERY,
    ENTITY_REFERENCE, USER_REFERENCE,
    JSON,
    ARRAY_OF_OBJECTS   // nested array; item_schema holds each item's field definitions
}
