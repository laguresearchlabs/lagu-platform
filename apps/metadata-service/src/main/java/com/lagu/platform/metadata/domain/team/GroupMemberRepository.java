package com.lagu.platform.metadata.domain.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    Optional<GroupMember> findByGroupIdAndUserId(UUID groupId, UUID userId);

    List<GroupMember> findByOrgIdAndUserId(UUID orgId, UUID userId);

    boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);
}
