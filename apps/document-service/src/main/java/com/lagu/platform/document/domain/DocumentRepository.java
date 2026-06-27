package com.lagu.platform.document.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByUserIdAndOrgIdOrderByUploadedAtDesc(UUID userId, UUID orgId);

    List<Document> findByUserIdAndOrgIdAndDocumentType(UUID userId, UUID orgId, String documentType);

    Page<Document> findByOrgIdAndStatusOrderByUploadedAtAsc(UUID orgId, String status, Pageable pageable);

    Optional<Document> findByIdAndOrgId(UUID id, UUID orgId);

    @Modifying
    @Query("UPDATE Document d SET d.status = 'EXPIRED' WHERE d.expiryDate < :today AND d.status NOT IN ('EXPIRED', 'REJECTED')")
    int markExpired(LocalDate today);
}
