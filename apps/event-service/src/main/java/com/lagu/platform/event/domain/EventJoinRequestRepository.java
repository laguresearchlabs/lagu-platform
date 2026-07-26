package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventJoinRequestRepository extends JpaRepository<EventJoinRequest, UUID> {
    List<EventJoinRequest> findByOrgIdAndStatus(UUID orgId, String status);
    Optional<EventJoinRequest> findByOrgIdAndUserId(UUID orgId, UUID userId);
}
