package com.lagu.platform.document.service;

import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.document.domain.Document;
import com.lagu.platform.document.domain.DocumentRepository;
import com.lagu.platform.document.dto.DocumentDto;
import com.lagu.platform.document.dto.DocumentSubmissionStatusResponse;
import com.lagu.platform.document.dto.DocumentSubmissionStatusResponse.DocumentTypeStatus;
import com.lagu.platform.document.event.DocumentEventPublisher;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DocumentService {

    /** Document types with required flag and display label. */
    private static final List<DocumentConfig> DOCUMENT_TYPES = List.of(
            new DocumentConfig("RESUME",               "Resume / CV",                              true),
            new DocumentConfig("IDENTITY_PROOF",       "Government-issued Identity Proof",         true),
            new DocumentConfig("PHOTOGRAPH",           "Passport-size Photograph",                 true),
            new DocumentConfig("ACADEMIC_CERTIFICATE", "Academic Certificates / Mark Sheets",      false),
            new DocumentConfig("ADDRESS_PROOF",        "Address Proof",                            false),
            new DocumentConfig("OTHER",                "Additional Documents (requested by HR)",   false)
    );

    private final DocumentRepository     repository;
    private final DocumentStorageService storageService;
    private final DocumentEventPublisher publisher;

    @Transactional
    public DocumentDto upload(MultipartFile file,
                              String documentType,
                              String identitySubType,
                              LocalDate expiryDate) {
        PlatformSecurityContext ctx = requireContext();

        validateDocumentType(documentType, identitySubType);

        String fileUrl = storageService.upload(file, ctx.getUserId(), documentType);

        Document doc = new Document();
        doc.setOrgId(ctx.getOrgId());
        doc.setUserId(ctx.getUserId());
        doc.setDocumentType(documentType.toUpperCase());
        doc.setIdentitySubType(identitySubType != null ? identitySubType.toUpperCase() : null);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileUrl(fileUrl);
        doc.setMimeType(file.getContentType());
        doc.setFileSizeBytes(file.getSize());
        doc.setExpiryDate(expiryDate);
        doc.setStatus("UPLOADED");

        Document saved = repository.save(doc);
        publisher.publish(saved, "DOCUMENT_UPLOADED");
        return DocumentDto.from(saved);
    }

    public List<DocumentDto> listMyDocuments() {
        PlatformSecurityContext ctx = requireContext();
        return repository.findByUserIdAndOrgIdOrderByUploadedAtDesc(ctx.getUserId(), ctx.getOrgId())
                .stream().map(DocumentDto::from).toList();
    }

    public DocumentDto getById(UUID id) {
        PlatformSecurityContext ctx = requireContext();
        return DocumentDto.from(findForContext(id, ctx));
    }

    public DocumentSubmissionStatusResponse getSubmissionStatus() {
        PlatformSecurityContext ctx = requireContext();
        List<Document> myDocs = repository.findByUserIdAndOrgIdOrderByUploadedAtDesc(
                ctx.getUserId(), ctx.getOrgId());

        Map<String, Document> latestByType = new LinkedHashMap<>();
        for (Document d : myDocs) {
            latestByType.putIfAbsent(d.getDocumentType(), d);
        }

        List<DocumentTypeStatus> statuses = new ArrayList<>();
        boolean allRequiredSubmitted = true;
        boolean allRequiredVerified  = true;

        for (DocumentConfig cfg : DOCUMENT_TYPES) {
            Document doc = latestByType.get(cfg.type());
            String effectiveStatus = doc != null ? doc.getStatus() : "MISSING";

            if (cfg.required()) {
                if ("MISSING".equals(effectiveStatus)) {
                    allRequiredSubmitted = false;
                    allRequiredVerified  = false;
                } else if (!"VERIFIED".equals(effectiveStatus)) {
                    allRequiredVerified = false;
                }
            }

            statuses.add(DocumentTypeStatus.builder()
                    .documentType(cfg.type())
                    .label(cfg.label())
                    .required(cfg.required())
                    .status(effectiveStatus)
                    .documentId(doc != null ? doc.getId() : null)
                    .identitySubType(doc != null ? doc.getIdentitySubType() : null)
                    .rejectionReason(doc != null ? doc.getRejectionReason() : null)
                    .uploadedAt(doc != null ? doc.getUploadedAt() : null)
                    .build());
        }

        return DocumentSubmissionStatusResponse.builder()
                .documents(statuses)
                .allRequiredSubmitted(allRequiredSubmitted)
                .allRequiredVerified(allRequiredVerified)
                .build();
    }

    public PageResult<DocumentDto> getPendingReview(int page, int size) {
        PlatformSecurityContext ctx = requireContext();
        var paged = repository.findByOrgIdAndStatusOrderByUploadedAtAsc(
                ctx.getOrgId(), "UPLOADED",
                PageRequest.of(page, size, Sort.by("uploadedAt").ascending()));
        return PageResult.from(paged.map(DocumentDto::from));
    }

    @Transactional
    public DocumentDto startReview(UUID id) {
        PlatformSecurityContext ctx = requireContext();
        Document doc = findForContext(id, ctx);
        doc.setStatus("UNDER_REVIEW");
        doc.setReviewedBy(ctx.getUserId());
        return DocumentDto.from(repository.save(doc));
    }

    @Transactional
    public DocumentDto verify(UUID id) {
        PlatformSecurityContext ctx = requireContext();
        Document doc = findForContext(id, ctx);
        doc.setStatus("VERIFIED");
        doc.setReviewedBy(ctx.getUserId());
        doc.setReviewedAt(Instant.now());
        doc.setRejectionReason(null);
        Document saved = repository.save(doc);
        publisher.publish(saved, "DOCUMENT_VERIFIED");
        return DocumentDto.from(saved);
    }

    @Transactional
    public DocumentDto reject(UUID id, String reason) {
        PlatformSecurityContext ctx = requireContext();
        Document doc = findForContext(id, ctx);
        doc.setStatus("REJECTED");
        doc.setReviewedBy(ctx.getUserId());
        doc.setReviewedAt(Instant.now());
        doc.setRejectionReason(reason);
        Document saved = repository.save(doc);
        publisher.publish(saved, "DOCUMENT_REJECTED");
        return DocumentDto.from(saved);
    }

    /** Nightly: mark documents with passed expiry dates as EXPIRED. */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void expireDocuments() {
        int count = repository.markExpired(LocalDate.now());
        if (count > 0) log.info("Marked {} document(s) as EXPIRED", count);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Document findForContext(UUID id, PlatformSecurityContext ctx) {
        if (ctx.isPlatformAdmin()) {
            return repository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Document", id.toString()));
        }
        return repository.findByIdAndOrgId(id, ctx.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", id.toString()));
    }

    private PlatformSecurityContext requireContext() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getUserId() == null) {
            throw new com.lagu.platform.common.exception.ValidationException("Authentication required");
        }
        return ctx;
    }

    private void validateDocumentType(String documentType, String identitySubType) {
        Set<String> validTypes = Set.of(
                "RESUME", "IDENTITY_PROOF", "PHOTOGRAPH",
                "ACADEMIC_CERTIFICATE", "ADDRESS_PROOF", "OTHER");

        if (!validTypes.contains(documentType.toUpperCase())) {
            throw new com.lagu.platform.common.exception.ValidationException(
                    "Invalid documentType: " + documentType + ". Must be one of " + validTypes);
        }

        if ("IDENTITY_PROOF".equalsIgnoreCase(documentType)) {
            Set<String> validSubTypes = Set.of("AADHAAR", "PASSPORT", "DRIVING_LICENSE", "VOTER_ID", "PAN_CARD");
            if (identitySubType == null || !validSubTypes.contains(identitySubType.toUpperCase())) {
                throw new com.lagu.platform.common.exception.ValidationException(
                        "identitySubType is required for IDENTITY_PROOF. Must be one of " + validSubTypes);
            }
        }
    }

    private record DocumentConfig(String type, String label, boolean required) {}
}
