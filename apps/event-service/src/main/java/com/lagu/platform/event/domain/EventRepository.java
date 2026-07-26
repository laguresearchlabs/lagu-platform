package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
    Optional<Event> findByOrgId(UUID orgId);
    List<Event> findByOwnerUserId(UUID ownerUserId);
}
