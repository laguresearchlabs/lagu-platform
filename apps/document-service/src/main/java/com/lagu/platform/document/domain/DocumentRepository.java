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

    List<Document> findByUserIdAndTenantIdOrderByUploadedAtDesc(UUID userId, UUID tenantId);

    List<Document> findByUserIdAndTenantIdAndDocumentType(UUID userId, UUID tenantId, String documentType);

    Page<Document> findByTenantIdAndStatusOrderByUploadedAtAsc(UUID tenantId, String status, Pageable pageable);

    /** Platform-admin cross-org pending queue — see DocumentService.getPendingReview(). */
    Page<Document> findByStatusOrderByUploadedAtAsc(String status, Pageable pageable);

    /** Every document (any user, any status) for one org — powers the admin KYC panel on a
     *  vendor's detail page. See DocumentService.listForTenantAdmin(). */
    List<Document> findByTenantIdOrderByUploadedAtDesc(UUID tenantId);

    Optional<Document> findByIdAndTenantId(UUID id, UUID tenantId);

    @Modifying
    @Query("UPDATE Document d SET d.status = 'EXPIRED' WHERE d.expiryDate < :today AND d.status NOT IN ('EXPIRED', 'REJECTED')")
    int markExpired(LocalDate today);
}
