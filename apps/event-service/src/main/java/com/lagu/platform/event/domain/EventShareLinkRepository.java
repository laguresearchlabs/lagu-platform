package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventShareLinkRepository extends JpaRepository<EventShareLink, UUID> {

    /** The anonymous preview path. Indexed by the UNIQUE constraint on token_hash. */
    Optional<EventShareLink> findByTokenHash(String tokenHash);

    List<EventShareLink> findByEventIdOrderByCreatedAtDesc(UUID eventId);

    /** Scoped by event so a link id from one event cannot be revoked through another's URL. */
    Optional<EventShareLink> findByIdAndEventId(UUID id, UUID eventId);
}
