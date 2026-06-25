package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovalDefinitionRepository extends JpaRepository<ApprovalDefinition, UUID> {
}
