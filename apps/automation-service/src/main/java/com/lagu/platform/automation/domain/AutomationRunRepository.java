package com.lagu.platform.automation.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AutomationRunRepository extends JpaRepository<AutomationRun, UUID> {

    @Query("SELECT r FROM AutomationRun r WHERE r.orgId = :orgId ORDER BY r.startedAt DESC")
    Page<AutomationRun> findByOrgId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("""
            SELECT COUNT(r) FROM AutomationRun r
            WHERE r.trigger.id = :triggerId AND r.recordId = :recordId AND r.startedAt > :since
            """)
    long countRecentRuns(@Param("triggerId") UUID triggerId, @Param("recordId") UUID recordId,
                          @Param("since") Instant since);
}
