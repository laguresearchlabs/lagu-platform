package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventJoinRequestRepository extends JpaRepository<EventJoinRequest, UUID> {
    List<EventJoinRequest> findByTenantIdAndStatus(UUID tenantId, String status);
    Optional<EventJoinRequest> findByTenantIdAndUserId(UUID tenantId, UUID userId);
}
