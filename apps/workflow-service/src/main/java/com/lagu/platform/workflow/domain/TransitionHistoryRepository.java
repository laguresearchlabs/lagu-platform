package com.lagu.platform.workflow.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransitionHistoryRepository extends JpaRepository<TransitionHistory, UUID> {

    Page<TransitionHistory> findByRecordIdOrderByTransitionedAtDesc(UUID recordId, Pageable pageable);
}
