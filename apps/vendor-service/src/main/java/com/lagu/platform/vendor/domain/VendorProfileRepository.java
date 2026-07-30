package com.lagu.platform.vendor.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VendorProfileRepository extends JpaRepository<VendorProfile, UUID> {
    Optional<VendorProfile> findByOwnerUserId(UUID userId);

    /** Admin listing — see VendorService.listForAdmin(). Filters are optional (null = no
     *  constraint); search matches business name, case-insensitive substring. :search is cast
     *  explicitly — used both bare (IS NULL check) and wrapped in LOWER()/CONCAT() below, and
     *  with a null argument Postgres can't infer a consistent type across both usages, so it
     *  falls back to bytea and LOWER(bytea) fails with "function lower(bytea) does not exist". */
    @Query("SELECT v FROM VendorProfile v " +
           "WHERE (:status IS NULL OR v.status = :status) " +
           "AND (:search IS NULL OR LOWER(v.businessName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) " +
           "ORDER BY v.createdAt DESC")
    Page<VendorProfile> search(@Param("status") String status, @Param("search") String search, Pageable pageable);
}
