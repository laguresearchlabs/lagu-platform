package com.lagu.platform.metadata.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.orgId = :orgId AND ur.userId = :userId")
    List<UserRole> findByOrgIdAndUserId(UUID orgId, UUID userId);

    @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.role.id = :roleId")
    List<UserRole> findByRoleId(UUID roleId);

    Optional<UserRole> findByOrgIdAndUserIdAndRoleId(UUID orgId, UUID userId, UUID roleId);

    boolean existsByOrgIdAndUserIdAndRoleId(UUID orgId, UUID userId, UUID roleId);
}
