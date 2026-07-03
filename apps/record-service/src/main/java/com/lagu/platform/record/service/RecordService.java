package com.lagu.platform.record.service;

import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordAudit;
import com.lagu.platform.record.domain.RecordAuditRepository;
import com.lagu.platform.record.domain.RecordRepository;
import com.lagu.platform.record.domain.RecordVerificationRepository;
import com.lagu.platform.record.dto.CreateRecordRequest;
import com.lagu.platform.record.dto.RecordResponse;
import com.lagu.platform.record.dto.StatusTransitionRequest;
import com.lagu.platform.record.dto.UpdateRecordRequest;
import com.lagu.platform.record.event.RecordEventPublisher;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordService {

    private final RecordRepository recordRepository;
    private final RecordAuditRepository auditRepository;
    private final RecordVerificationRepository verificationRepository;
    private final RecordValidator validator;
    private final RecordEventPublisher eventPublisher;

    public RecordResponse getById(UUID id) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Record record = findForContext(id, ctx);
        return toResponse(record);
    }

    public PageResult<RecordResponse> list(String objectType, String status, int page, int size) {
        UUID orgId = requireOrgContext().getOrgId();
        PageRequest pageReq = PageRequest.of(page, size, Sort.by("createdAt").descending());

        var results = (status != null)
                ? recordRepository.findByOrgIdAndObjectTypeAndStatus(orgId, objectType.toUpperCase(), status, pageReq)
                : recordRepository.findByOrgIdAndObjectTypeAndStatusNot(orgId, objectType.toUpperCase(), "DELETED", pageReq);

        return PageResult.from(results.map(this::toResponse));
    }

    @Transactional
    public RecordResponse create(CreateRecordRequest req) {
        PlatformSecurityContext ctx = requireOrgContext();
        validator.validate(req.getObjectType(), req.getData());

        // Initial status is owned by the workflow engine. Letting callers pick one would let a
        // vendor create a record directly in ACTIVE/PUBLISHED, skipping approval entirely.
        if (req.getStatus() != null && !"DRAFT".equalsIgnoreCase(req.getStatus())
                && !ctx.isPlatformAdmin()) {
            throw new ValidationException(
                    "Initial status cannot be set on create; records start in DRAFT and " +
                    "move via workflow transitions");
        }

        Record record = new Record();
        record.setOrgId(ctx.getOrgId());
        record.setObjectType(req.getObjectType().toUpperCase());
        record.setStatus(req.getStatus() != null ? req.getStatus().toUpperCase() : "DRAFT");
        record.setData(req.getData());
        record.setCreatedBy(ctx.getUserId());
        record.setUpdatedBy(ctx.getUserId());

        Record saved = recordRepository.save(record);
        audit(saved.getId(), "CREATED", null, saved.getData(), null, null, ctx);
        eventPublisher.publishCreated(saved);
        return toResponse(saved);
    }

    @Transactional
    public RecordResponse update(UUID id, UpdateRecordRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Record record = findForContext(id, ctx);
        validator.validate(record.getObjectType(), req.getData());

        Map<String, Object> oldData = new HashMap<>(record.getData());
        record.setData(req.getData());
        record.setUpdatedBy(ctx != null ? ctx.getUserId() : null);

        Record saved = recordRepository.save(record);
        audit(saved.getId(), "UPDATED", oldData, saved.getData(), null, null, ctx);
        eventPublisher.publishUpdated(saved);
        return toResponse(saved);
    }

    @Transactional
    public RecordResponse patch(UUID id, Map<String, Object> partialData) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Record record = findForContext(id, ctx);

        Map<String, Object> merged = new HashMap<>(record.getData());
        merged.putAll(partialData);
        validator.validate(record.getObjectType(), merged);

        Map<String, Object> oldData = new HashMap<>(record.getData());
        record.setData(merged);
        record.setUpdatedBy(ctx != null ? ctx.getUserId() : null);

        Record saved = recordRepository.save(record);
        audit(saved.getId(), "UPDATED", oldData, saved.getData(), null, null, ctx);
        eventPublisher.publishUpdated(saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Record record = findForContext(id, ctx);
        String oldStatus = record.getStatus();
        record.setStatus("DELETED");
        record.setUpdatedBy(ctx != null ? ctx.getUserId() : null);
        Record saved = recordRepository.save(record);
        audit(saved.getId(), "DELETED", null, null, oldStatus, "DELETED", ctx);
        eventPublisher.publishDeleted(saved);
    }

    @Transactional
    public RecordResponse requestTransition(UUID id, StatusTransitionRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Record record = findForContext(id, ctx);
        // Publish event to workflow-service via Kafka; status updated when workflow responds
        eventPublisher.publishTransitionRequested(record, req.getTrigger(), req.getComment(), ctx);
        return toResponse(record);
    }

    public PageResult<RecordResponse> getHistory(UUID id, int page, int size) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        findForContext(id, ctx); // verify access
        var results = auditRepository.findByRecordIdOrderByChangedAtDesc(
                id, PageRequest.of(page, size));
        return PageResult.from(results.map(a -> toResponse(toRecord(a))));
    }

    /**
     * Loads a record enforcing tenancy: everyone except PLATFORM_ADMIN must carry an org
     * context and only sees records of that org. A caller without an org gets 403, never an
     * unscoped lookup — an org-less-but-authenticated user must not reach other tenants' data.
     */
    private Record findForContext(UUID id, PlatformSecurityContext ctx) {
        if (ctx == null) {
            throw new PlatformException("AUTH_REQUIRED", "Authentication required",
                    HttpStatus.UNAUTHORIZED);
        }
        if (ctx.isPlatformAdmin()) {
            return recordRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Record", id.toString()));
        }
        if (ctx.getOrgId() == null) {
            throw new PlatformException("ORG_CONTEXT_REQUIRED",
                    "An organization context is required to access records", HttpStatus.FORBIDDEN);
        }
        return recordRepository.findByIdAndOrgId(id, ctx.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Record", id.toString()));
    }

    private PlatformSecurityContext requireOrgContext() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null) {
            throw new PlatformException("AUTH_REQUIRED", "Authentication required",
                    HttpStatus.UNAUTHORIZED);
        }
        if (ctx.getOrgId() == null) {
            throw new PlatformException("ORG_CONTEXT_REQUIRED",
                    "An organization context is required for this operation", HttpStatus.FORBIDDEN);
        }
        return ctx;
    }

    private void audit(UUID recordId, String action, Map<String, Object> oldData,
                       Map<String, Object> newData, String oldStatus, String newStatus,
                       PlatformSecurityContext ctx) {
        RecordAudit a = new RecordAudit();
        a.setRecordId(recordId);
        a.setAction(action);
        a.setOldData(oldData);
        a.setNewData(newData);
        a.setOldStatus(oldStatus);
        a.setNewStatus(newStatus);
        a.setChangedBy(ctx != null ? ctx.getUserId() : null);
        auditRepository.save(a);
    }

    public RecordResponse toResponse(Record r) {
        RecordResponse.RecordResponseBuilder builder = RecordResponse.builder()
                .id(r.getId())
                .orgId(r.getOrgId())
                .objectType(r.getObjectType())
                .status(r.getStatus())
                .data(r.getData())
                .createdBy(r.getCreatedBy())
                .updatedBy(r.getUpdatedBy())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt());

        verificationRepository.findByRecordId(r.getId()).ifPresent(v -> builder
                .verificationTier(v.getTier())
                .verificationStatus(v.getStatus())
                .verificationExpiresAt(v.getExpiresAt()));

        return builder.build();
    }

    private Record toRecord(RecordAudit audit) {
        Record r = new Record();
        r.setId(audit.getRecordId());
        r.setData(audit.getNewData() != null ? audit.getNewData() : Map.of());
        r.setStatus(audit.getNewStatus());
        r.setUpdatedBy(audit.getChangedBy());
        r.setUpdatedAt(audit.getChangedAt());
        return r;
    }
}
