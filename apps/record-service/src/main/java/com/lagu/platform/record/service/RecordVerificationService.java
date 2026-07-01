package com.lagu.platform.record.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordRepository;
import com.lagu.platform.record.domain.RecordVerification;
import com.lagu.platform.record.domain.RecordVerificationRepository;
import com.lagu.platform.record.dto.SetVerificationRequest;
import com.lagu.platform.record.dto.VerificationResponse;
import com.lagu.platform.record.event.RecordEventPublisher;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RecordVerificationService {

    private final RecordVerificationRepository verificationRepository;
    private final RecordRepository recordRepository;
    private final RecordEventPublisher eventPublisher;

    public VerificationResponse getByRecordId(UUID recordId) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;

        RecordVerification v = (orgId != null && !isPlatformAdmin(ctx))
                ? verificationRepository.findByRecordIdAndOrgId(recordId, orgId)
                        .orElseThrow(() -> new ResourceNotFoundException("RecordVerification", recordId.toString()))
                : verificationRepository.findByRecordId(recordId)
                        .orElseThrow(() -> new ResourceNotFoundException("RecordVerification", recordId.toString()));

        return toResponse(v);
    }

    @Transactional
    public VerificationResponse set(UUID recordId, SetVerificationRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();

        Record record = recordRepository.findById(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Record", recordId.toString()));

        RecordVerification v = verificationRepository.findByRecordId(recordId)
                .orElseGet(() -> {
                    RecordVerification nv = new RecordVerification();
                    nv.setRecordId(recordId);
                    nv.setOrgId(record.getOrgId());
                    return nv;
                });

        String previousTier = v.getTier();
        v.setTier(req.getTier().toUpperCase());
        v.setStatus("NONE".equals(v.getTier()) ? "UNVERIFIED" : "VERIFIED");
        v.setVerifiedBy(ctx != null ? ctx.getUserId() : null);
        v.setVerifiedAt("NONE".equals(v.getTier()) ? null : OffsetDateTime.now());
        v.setExpiresAt(req.getExpiresAt());
        v.setNotes(req.getNotes());

        RecordVerification saved = verificationRepository.save(v);

        if (!previousTier.equals(saved.getTier())) {
            eventPublisher.publishVerificationChanged(record, previousTier, saved.getTier(), ctx);
            log.info("Verification tier changed for record {} : {} → {}", recordId, previousTier, saved.getTier());
        }

        return toResponse(saved);
    }

    @Transactional
    public VerificationResponse revoke(UUID recordId, String reason) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();

        RecordVerification v = verificationRepository.findByRecordId(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("RecordVerification", recordId.toString()));

        Record record = recordRepository.findById(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Record", recordId.toString()));

        String previousTier = v.getTier();
        v.setStatus("REVOKED");
        v.setNotes(reason);
        RecordVerification saved = verificationRepository.save(v);

        eventPublisher.publishVerificationChanged(record, previousTier, "REVOKED", ctx);
        return toResponse(saved);
    }

    /** Called by automation-service EXPIRE_VERIFICATION action. */
    @Transactional
    public int expireOverdue() {
        List<RecordVerification> expired = verificationRepository.findExpired(OffsetDateTime.now());
        int count = verificationRepository.markExpired(OffsetDateTime.now());
        expired.forEach(v -> {
            recordRepository.findById(v.getRecordId()).ifPresent(record ->
                    eventPublisher.publishVerificationChanged(record, v.getTier(), "EXPIRED", null));
        });
        if (count > 0) log.info("Expired {} record verification(s)", count);
        return count;
    }

    private boolean isPlatformAdmin(PlatformSecurityContext ctx) {
        return ctx != null && ctx.isPlatformAdmin();
    }

    private VerificationResponse toResponse(RecordVerification v) {
        return VerificationResponse.builder()
                .id(v.getId())
                .recordId(v.getRecordId())
                .tier(v.getTier())
                .status(v.getStatus())
                .verifiedBy(v.getVerifiedBy())
                .verifiedAt(v.getVerifiedAt())
                .expiresAt(v.getExpiresAt())
                .notes(v.getNotes())
                .updatedAt(v.getUpdatedAt())
                .build();
    }
}
