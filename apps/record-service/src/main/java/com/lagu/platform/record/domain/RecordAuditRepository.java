package com.lagu.platform.record.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecordAuditRepository extends JpaRepository<RecordAudit, UUID> {

    Page<RecordAudit> findByRecordIdOrderByChangedAtDesc(UUID recordId, Pageable pageable);
}
