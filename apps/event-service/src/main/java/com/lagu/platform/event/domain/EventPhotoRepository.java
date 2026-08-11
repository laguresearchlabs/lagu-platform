package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventPhotoRepository extends JpaRepository<EventPhoto, UUID> {

    List<EventPhoto> findByEventIdAndVisibilityOrderByCreatedAtDesc(UUID eventId, String visibility);

    List<EventPhoto> findByEventIdOrderByCreatedAtDesc(UUID eventId);

    Optional<EventPhoto> findByIdAndEventId(UUID id, UUID eventId);

    /** Deleting an event cascades the rows; this is for sweeping their objects first. */
    List<EventPhoto> findByEventId(UUID eventId);
}
