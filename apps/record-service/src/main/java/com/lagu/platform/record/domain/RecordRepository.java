package com.lagu.platform.record.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RecordRepository extends JpaRepository<Record, UUID> {

    @Query("SELECT r FROM Record r WHERE r.tenantId = :tenantId AND r.id = :id AND r.status != 'DELETED'")
    Optional<Record> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Org-unscoped lookup for cross-org relationship targets (e.g. an event record in its own
     * org linking to a vendor's VENUE/PHOTOGRAPHER/etc. record in a different org). Callers must
     * apply their own visibility gating (see RelationshipService.create) — this alone is not an
     * authorization check.
     */
    @Query("SELECT r FROM Record r WHERE r.id = :id AND r.status != 'DELETED'")
    Optional<Record> findByIdExcludingDeleted(UUID id);

    Page<Record> findByTenantIdAndObjectTypeAndStatusNot(UUID tenantId, String objectType,
                                                       String excludeStatus, Pageable pageable);

    Page<Record> findByTenantIdAndObjectTypeAndStatus(UUID tenantId, String objectType,
                                                    String status, Pageable pageable);

    Page<Record> findByTenantIdAndObjectType(UUID tenantId, String objectType, Pageable pageable);

    /** Platform-admin cross-tenant listing — see RecordService.list(). Null status excludes
     *  DELETED (matching findByTenantIdAndObjectTypeAndStatusNot's default-view semantics); an
     *  explicit status matches exactly, DELETED included if that's what was asked for. */
    @Query("SELECT r FROM Record r WHERE r.objectType = :objectType " +
           "AND ((:status IS NULL AND r.status != 'DELETED') OR r.status = :status) " +
           "ORDER BY r.createdAt DESC")
    Page<Record> searchAdmin(@Param("objectType") String objectType, @Param("status") String status, Pageable pageable);
}
