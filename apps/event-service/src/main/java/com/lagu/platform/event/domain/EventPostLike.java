package com.lagu.platform.event.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_post_like")
@Data
@NoArgsConstructor
@IdClass(EventPostLike.Id.class)
public class EventPostLike {

    @jakarta.persistence.Id
    @Column(name = "post_record_id")
    private UUID postRecordId;

    @jakarta.persistence.Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public EventPostLike(UUID postRecordId, UUID userId) {
        this.postRecordId = postRecordId;
        this.userId = userId;
    }

    @Data
    @NoArgsConstructor
    public static class Id implements Serializable {
        private UUID postRecordId;
        private UUID userId;

        public Id(UUID postRecordId, UUID userId) {
            this.postRecordId = postRecordId;
            this.userId = userId;
        }
    }
}
