package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ApprovalInstanceRepository extends JpaRepository<ApprovalInstance, UUID> {

    List<ApprovalInstance> findByRecordIdAndStatus(UUID recordId, String status);

    @Query("""
            SELECT ai FROM ApprovalInstance ai
            JOIN FETCH ai.approvalDefinition ad
            JOIN ad.steps s
            WHERE ai.orgId = :orgId AND ai.status = 'PENDING' AND s.approverRole IN :roles
            """)
    List<ApprovalInstance> findPendingForRoles(UUID orgId, List<String> roles);

    @Query("""
            SELECT ai FROM ApprovalInstance ai
            JOIN FETCH ai.approvalDefinition ad
            JOIN ad.steps s
            WHERE ai.orgId = :orgId AND ai.status = 'PENDING' AND s.approverRole IN :roles AND ai.createdAt < :cutoff
            """)
    List<ApprovalInstance> findPendingForRolesOlderThan(UUID orgId, List<String> roles, java.time.OffsetDateTime cutoff);
}
