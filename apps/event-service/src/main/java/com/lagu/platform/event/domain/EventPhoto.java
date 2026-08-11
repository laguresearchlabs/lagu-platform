package com.lagu.platform.event.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One photo in an event's album.
 *
 * <p>Holds storage <b>keys</b>, never URLs — a signed URL lasts minutes and this row outlives it
 * by design. URLs are minted per response.
 *
 * <p>Event posts deliberately do not use this: they are EVENT_POST records, so their photos live
 * in a MEDIA_GALLERY field and are served by record-service. This exists because an Event is not
 * a record and has nowhere else to put them.
 */
@Entity
@Table(name = "event_photo")
@Data
@NoArgsConstructor
public class EventPhoto {

    public static final String PUBLIC = "PUBLIC";
    public static final String PRIVATE = "PRIVATE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    /** Card-sized derivative, or null when the format could not be decoded. */
    @Column(name = "card_key", length = 1024)
    private String cardKey;

    @Column(name = "full_key", length = 1024)
    private String fullKey;

    /** PUBLIC or PRIVATE. The overview widget is shown to every member, so this is what keeps a
     *  private photo out of it rather than a filter the caller supplies. */
    @Column(nullable = false, length = 20)
    private String visibility = PUBLIC;

    @Column(length = 300)
    private String caption;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    /** The key to serve for display: the derivative when one exists, else the original. */
    public String cardKeyOrOriginal() {
        return cardKey != null ? cardKey : storageKey;
    }

    public String fullKeyOrOriginal() {
        return fullKey != null ? fullKey : storageKey;
    }
}
