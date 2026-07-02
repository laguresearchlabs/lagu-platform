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

    private final DocumentRepository     repository;
    private final DocumentStorageService storageService;
    private final DocumentEventPublisher publisher;
    private final DocumentTypeRegistry   docTypeRegistry;

    @Transactional
    public DocumentDto upload(MultipartFile file,
                              String documentType,
                              String identitySubType,
                              LocalDate expiryDate) {
        PlatformSecurityContext ctx = requireContext();

        validateDocumentType(documentType, identitySubType);
        validateFile(file);

        String fileUrl = storageService.upload(file, ctx.getUserId(), documentType);

        Document doc = new Document();
        doc.setOrgId(ctx.getOrgId());
        doc.setUserId(ctx.getUserId());
        doc.setDocumentType(documentType.toUpperCase());
        doc.setIdentitySubType(identitySubType != null ? identitySubType.toUpperCase() : null);
        doc.setFileName(sanitizeFileName(file.getOriginalFilename()));
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

        for (DocumentTypeRegistry.DocumentConfig cfg : docTypeRegistry.all()) {
            Document doc = latestByType.get(cfg.code());
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
                    .documentType(cfg.code())
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

    // Identity/verification documents: photos and scans only. No executables, HTML/SVG (stored
    // XSS risk if ever rendered inline), or arbitrary office/archive formats.
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "application/pdf");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "pdf");
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new com.lagu.platform.common.exception.ValidationException("File must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new com.lagu.platform.common.exception.ValidationException(
                    "File exceeds maximum size of " + (MAX_FILE_SIZE_BYTES / (1024 * 1024)) + "MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new com.lagu.platform.common.exception.ValidationException(
                    "Unsupported file type: " + contentType + ". Allowed: " + ALLOWED_CONTENT_TYPES);
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new com.lagu.platform.common.exception.ValidationException(
                    "Unsupported file extension: " + extension + ". Allowed: " + ALLOWED_EXTENSIONS);
        }
    }

    private String extensionOf(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toLowerCase();
    }

    /** Strips path separators and control characters; keeps the original name otherwise readable. */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) return null;
        String base = fileName.replace("\\", "/");
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }

    private void validateDocumentType(String documentType, String identitySubType) {
        Set<String> validTypes = docTypeRegistry.validCodes();

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

}
