package com.lagu.platform.metadata.domain.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupDefinitionRepository extends JpaRepository<GroupDefinition, UUID> {

    List<GroupDefinition> findByOrgIdAndActiveTrue(UUID orgId);

    Optional<GroupDefinition> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByNameAndOrgId(String name, UUID orgId);
}
