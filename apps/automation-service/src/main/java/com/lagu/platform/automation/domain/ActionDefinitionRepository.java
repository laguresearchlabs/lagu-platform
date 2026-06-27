package com.lagu.platform.automation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActionDefinitionRepository extends JpaRepository<ActionDefinition, UUID> {
    List<ActionDefinition> findByTriggerIdAndIsActiveTrueOrderByExecutionOrderAsc(UUID triggerId);
}
