package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventMemberRepository extends JpaRepository<EventMember, UUID> {
    List<EventMember> findByTenantId(UUID tenantId);
    Optional<EventMember> findByTenantIdAndUserId(UUID tenantId, UUID userId);
    Optional<EventMember> findByTenantIdAndUserIdAndStatus(UUID tenantId, UUID userId, String status);
    Optional<EventMember> findByTenantIdAndUserIdAndStatusNot(UUID tenantId, UUID userId, String excludedStatus);
    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);
    List<EventMember> findByUserId(UUID userId);
}
