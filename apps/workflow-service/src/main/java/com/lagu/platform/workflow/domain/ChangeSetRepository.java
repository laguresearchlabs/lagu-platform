package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChangeSetRepository extends JpaRepository<ChangeSet, UUID> {

    List<ChangeSet> findByRecordIdOrderBySubmittedAtDesc(UUID recordId);

    List<ChangeSet> findByOrgIdAndStatusOrderBySubmittedAtDesc(UUID orgId, String status);

    Optional<ChangeSet> findFirstByRecordIdAndStatus(UUID recordId, String status);

    List<ChangeSet> findByStatusOrderBySubmittedAtAsc(String status);
}
