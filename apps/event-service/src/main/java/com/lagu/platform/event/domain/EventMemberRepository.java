package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventMemberRepository extends JpaRepository<EventMember, UUID> {
    List<EventMember> findByOrgId(UUID orgId);
    Optional<EventMember> findByOrgIdAndUserId(UUID orgId, UUID userId);
    boolean existsByOrgIdAndUserId(UUID orgId, UUID userId);
    List<EventMember> findByUserId(UUID userId);
}
