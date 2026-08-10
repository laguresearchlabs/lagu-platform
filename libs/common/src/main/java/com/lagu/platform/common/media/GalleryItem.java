package com.lagu.platform.common.media;

import com.lagu.platform.common.exception.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One photograph in a MEDIA_GALLERY field.
 *
 * <p>Stored in the record's JSONB as a plain map so the field stays schema-driven like every
 * other — there is no gallery table, and a listing type gains a gallery by declaring a field,
 * not by a migration.
 *
 * <p>Lives in {@code libs/common} because that map is a contract between services, not a detail
 * of one. record-service writes it; listing-service reads it out of the frozen snapshot it
 * copied, to find a listing's cover photo. Two hand-maintained readers of the same JSONB is how
 * one of them quietly stops finding {@code isPrimary} after a rename.
 *
 * <p><b>Order is array position.</b> There is deliberately no {@code order} attribute: a stored
 * index that can disagree with the position it sits at is the same denormalisation trap as a
 * promoted column, and reordering would have to rewrite every item to keep the two in step.
 * Moving an item means moving it.
 *
 * @param id      addresses the item in a URL; the storage key contains slashes and cannot
 * @param key     object storage key — never a URL, which is signed per request on read
 * @param caption vendor-supplied, shown under the photo; also serves as its alt text
 * @param cardKey key of the card-sized derivative, or null when none could be built (a format
 *                with no decoder). Held per item rather than derived from {@code key} so a photo
 *                uploaded before derivatives existed, or one whose thumbnail failed, is
 *                distinguishable from one whose thumbnail is simply not fetched yet.
 * @param fullKey key of the display-sized derivative, same caveat
 */
public record GalleryItem(UUID id, String key, String caption, boolean primary,
                          String cardKey, String fullKey) {

    private static final String F_ID = "id";
    private static final String F_KEY = "key";
    private static final String F_CAPTION = "caption";
    private static final String F_PRIMARY = "isPrimary";
    private static final String F_CARD_KEY = "cardKey";
    private static final String F_FULL_KEY = "fullKey";

    /** Captions land in consumer HTML, so this bounds them; escaping is the renderer's job. */
    public static final int MAX_CAPTION_LENGTH = 300;

    public GalleryItem {
        if (caption != null && caption.length() > MAX_CAPTION_LENGTH) {
            throw new ValidationException(
                    "Caption exceeds " + MAX_CAPTION_LENGTH + " characters");
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(F_ID, id.toString());
        map.put(F_KEY, key);
        map.put(F_CAPTION, caption);
        map.put(F_PRIMARY, primary);
        map.put(F_CARD_KEY, cardKey);
        map.put(F_FULL_KEY, fullKey);
        return map;
    }

    /**
     * Reads the stored form back.
     *
     * <p>Tolerant on everything except the key: JSONB written by an older shape of this code, or
     * by a migration, should keep rendering rather than fail the whole read. A missing key is the
     * one thing that cannot be worked around — the item points at nothing.
     */
    public static GalleryItem fromMap(Map<String, Object> map) {
        Object key = map.get(F_KEY);
        if (!(key instanceof String k) || k.isBlank()) {
            throw new ValidationException("Gallery item is missing its storage key");
        }
        return new GalleryItem(
                parseId(map.get(F_ID)),
                k,
                str(map.get(F_CAPTION)),
                Boolean.TRUE.equals(map.get(F_PRIMARY)),
                str(map.get(F_CARD_KEY)),
                str(map.get(F_FULL_KEY)));
    }

    private static String str(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private static UUID parseId(Object raw) {
        if (raw == null) return UUID.randomUUID();
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }

    /** The whole field's value, in order. Anything unreadable is skipped rather than fatal. */
    @SuppressWarnings("unchecked")
    public static List<GalleryItem> listFrom(Object fieldValue) {
        if (!(fieldValue instanceof List<?> raw)) return new ArrayList<>();
        List<GalleryItem> items = new ArrayList<>();
        for (Object entry : raw) {
            if (entry instanceof Map<?, ?> map) {
                items.add(fromMap((Map<String, Object>) map));
            }
        }
        return items;
    }

    public static List<Map<String, Object>> toMaps(List<GalleryItem> items) {
        return items.stream().map(GalleryItem::toMap).toList();
    }

    public GalleryItem withCaption(String newCaption) {
        return new GalleryItem(id, key, newCaption, primary, cardKey, fullKey);
    }

    public GalleryItem withPrimary(boolean isPrimary) {
        return new GalleryItem(id, key, caption, isPrimary, cardKey, fullKey);
    }

    /**
     * The key to serve for display: the card derivative when one exists, otherwise the original.
     *
     * <p>Falling back rather than returning nothing is what keeps a photo in a format the
     * platform cannot thumbnail — or one uploaded before derivatives existed — rendering. It
     * costs bandwidth, not correctness.
     */
    public String cardKeyOrOriginal() {
        return cardKey != null ? cardKey : key;
    }

    public String fullKeyOrOriginal() {
        return fullKey != null ? fullKey : key;
    }

    /** Every object this item owns, for deletion — the original and whatever was derived. */
    public java.util.List<String> allKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>(3);
        keys.add(key);
        if (cardKey != null) keys.add(cardKey);
        if (fullKey != null) keys.add(fullKey);
        return keys;
    }

    /**
     * Exactly one item is primary, or none when the gallery is empty.
     *
     * <p>Applied after every mutation rather than trusted from the stored data. The cover photo
     * is what a search card renders, so "no primary" and "two primaries" are both states the
     * consumer app would have to invent a tiebreak for — better that they cannot occur. Falls
     * back to the first item, which is also what an empty gallery's first upload becomes.
     */
    public static List<GalleryItem> withSinglePrimary(List<GalleryItem> items) {
        if (items.isEmpty()) return items;
        int primaryIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).primary()) {
                primaryIndex = i;
                break;
            }
        }
        int chosen = primaryIndex < 0 ? 0 : primaryIndex;

        List<GalleryItem> normalized = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            normalized.add(items.get(i).withPrimary(i == chosen));
        }
        return normalized;
    }
}
