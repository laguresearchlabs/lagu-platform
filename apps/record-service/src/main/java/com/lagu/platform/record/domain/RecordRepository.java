package com.lagu.platform.record.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface RecordRepository extends JpaRepository<Record, UUID> {

    @Query("SELECT r FROM Record r WHERE r.orgId = :orgId AND r.id = :id AND r.status != 'DELETED'")
    Optional<Record> findByIdAndOrgId(UUID id, UUID orgId);

    Page<Record> findByOrgIdAndObjectTypeAndStatusNot(UUID orgId, String objectType,
                                                       String excludeStatus, Pageable pageable);

    Page<Record> findByOrgIdAndObjectTypeAndStatus(UUID orgId, String objectType,
                                                    String status, Pageable pageable);

    Page<Record> findByOrgIdAndObjectType(UUID orgId, String objectType, Pageable pageable);
}
