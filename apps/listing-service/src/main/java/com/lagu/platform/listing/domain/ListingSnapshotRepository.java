package com.lagu.platform.listing.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingSnapshotRepository extends JpaRepository<ListingSnapshot, UUID> {

    Optional<ListingSnapshot> findByRecordId(UUID recordId);

    List<ListingSnapshot> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

    /** Consumer search — published only, boosted by searchBoost DESC. */
    @Query("SELECT s FROM ListingSnapshot s WHERE s.objectType = :objectType " +
           "AND s.status = 'PUBLISHED' ORDER BY s.searchBoost DESC, s.publishedAt DESC")
    Page<ListingSnapshot> findPublishedByObjectType(@Param("objectType") String objectType, Pageable pageable);

    /** Consumer: cursor pagination using id for stable ordering after searchBoost. */
    @Query("SELECT s FROM ListingSnapshot s WHERE s.objectType = :objectType " +
           "AND s.status = 'PUBLISHED' AND s.id > :cursorId " +
           "ORDER BY s.searchBoost DESC, s.id ASC")
    List<ListingSnapshot> findPublishedAfterCursor(@Param("objectType") String objectType,
                                                    @Param("cursorId") UUID cursorId,
                                                    Pageable pageable);

    /** Platform-admin listing across every org — see ListingSnapshotService.listForAdmin().
     *  Filters are optional (null = no constraint). */
    @Query("SELECT s FROM ListingSnapshot s " +
           "WHERE (:objectType IS NULL OR s.objectType = :objectType) " +
           "AND (:tenantId IS NULL OR s.tenantId = :tenantId) " +
           "AND (:status IS NULL OR s.status = :status) " +
           "AND (:verificationTier IS NULL OR s.verificationTier = :verificationTier) " +
           "ORDER BY s.updatedAt DESC")
    Page<ListingSnapshot> search(@Param("objectType") String objectType,
                                  @Param("tenantId") UUID tenantId,
                                  @Param("status") String status,
                                  @Param("verificationTier") String verificationTier,
                                  Pageable pageable);
}
