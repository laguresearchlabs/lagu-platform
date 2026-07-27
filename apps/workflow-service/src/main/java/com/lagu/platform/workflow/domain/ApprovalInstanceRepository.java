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
            WHERE ai.tenantId = :tenantId AND ai.status = 'PENDING' AND s.approverRole IN :roles
            """)
    List<ApprovalInstance> findPendingForRoles(UUID tenantId, List<String> roles);

    @Query("""
            SELECT ai FROM ApprovalInstance ai
            JOIN FETCH ai.approvalDefinition ad
            JOIN ad.steps s
            WHERE ai.tenantId = :tenantId AND ai.status = 'PENDING' AND s.approverRole IN :roles AND ai.createdAt < :cutoff
            """)
    List<ApprovalInstance> findPendingForRolesOlderThan(UUID tenantId, List<String> roles, java.time.OffsetDateTime cutoff);

    /** Platform-wide (cross-org), for automation-service's approval-timeout escalation scheduler. */
    @Query("""
            SELECT ai FROM ApprovalInstance ai
            JOIN FETCH ai.approvalDefinition
            WHERE ai.status = 'PENDING' AND ai.createdAt < :cutoff
            """)
    List<ApprovalInstance> findPendingOlderThan(java.time.OffsetDateTime cutoff);
}
