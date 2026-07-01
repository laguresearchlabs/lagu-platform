package com.lagu.platform.record.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordVerificationRepository extends JpaRepository<RecordVerification, UUID> {

    Optional<RecordVerification> findByRecordId(UUID recordId);

    Optional<RecordVerification> findByRecordIdAndOrgId(UUID recordId, UUID orgId);

    @Query("SELECT v FROM RecordVerification v WHERE v.status = 'VERIFIED' AND v.expiresAt < :now")
    List<RecordVerification> findExpired(@Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE RecordVerification v SET v.status = 'EXPIRED', v.updatedAt = :now " +
           "WHERE v.status = 'VERIFIED' AND v.expiresAt < :now")
    int markExpired(@Param("now") OffsetDateTime now);
}
