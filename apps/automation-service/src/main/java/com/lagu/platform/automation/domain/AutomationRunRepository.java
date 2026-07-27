package com.lagu.platform.automation.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AutomationRunRepository extends JpaRepository<AutomationRun, UUID> {

    @Query("SELECT r FROM AutomationRun r WHERE r.tenantId = :tenantId ORDER BY r.startedAt DESC")
    Page<AutomationRun> findByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    /** Fetch-joins actionRuns for the single-run detail view — open-in-view is false, so the
     *  child rows must be pulled while this query's session is still open. */
    @Query("SELECT r FROM AutomationRun r LEFT JOIN FETCH r.actionRuns WHERE r.id = :id")
    Optional<AutomationRun> findByIdWithActionRuns(@Param("id") UUID id);

    @Query("""
            SELECT COUNT(r) FROM AutomationRun r
            WHERE r.trigger.id = :triggerId AND r.recordId = :recordId AND r.startedAt > :since
            """)
    long countRecentRuns(@Param("triggerId") UUID triggerId, @Param("recordId") UUID recordId,
                          @Param("since") Instant since);
}
