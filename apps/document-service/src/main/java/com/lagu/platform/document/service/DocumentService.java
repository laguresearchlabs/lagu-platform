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
        doc.setTenantId(ctx.getTenantId());
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
        return repository.findByUserIdAndTenantIdOrderByUploadedAtDesc(ctx.getUserId(), ctx.getTenantId())
                .stream().map(DocumentDto::from).toList();
    }

    public DocumentDto getById(UUID id) {
        PlatformSecurityContext ctx = requireContext();
        return DocumentDto.from(findForContext(id, ctx));
    }

    public DocumentSubmissionStatusResponse getSubmissionStatus() {
        PlatformSecurityContext ctx = requireContext();
        List<Document> myDocs = repository.findByUserIdAndTenantIdOrderByUploadedAtDesc(
                ctx.getUserId(), ctx.getTenantId());

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
                    .uploadedAt(doc != null && doc.getUploadedAt() != null
                            ? doc.getUploadedAt().atOffset(java.time.ZoneOffset.UTC) : null)
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
        PageRequest pageReq = PageRequest.of(page, size, Sort.by("uploadedAt").ascending());
        // A platform admin has no org of their own — findByTenantIdAndStatus(null, ...) would
        // silently match nothing, not "everything", so admin gets the genuinely unscoped query.
        var paged = ctx.isPlatformAdmin()
                ? repository.findByStatusOrderByUploadedAtAsc("UPLOADED", pageReq)
                : repository.findByTenantIdAndStatusOrderByUploadedAtAsc(ctx.getTenantId(), "UPLOADED", pageReq);
        return PageResult.from(paged.map(DocumentDto::from));
    }

    /** Platform-admin: every document for one org, regardless of uploader — the KYC review panel
     *  on a vendor's admin detail page. Caller must be authorized by the controller first. */
    public List<DocumentDto> listForTenantAdmin(UUID tenantId) {
        return repository.findByTenantIdOrderByUploadedAtDesc(tenantId)
                .stream().map(DocumentDto::from).toList();
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

    /**
     * DefaultPermissionEvaluator's DOCUMENT:READ grant is role-shaped only ("any authenticated
     * user can read", per its own comment, intending "read their own") — it has no way to know
     * whose document a given id belongs to, so ownership has to be enforced here. This
     * previously scoped by org only, meaning any employee could read any colleague's identity
     * document (Aadhaar/passport scan) and its signed fileUrl within the same org. Reviewers
     * (ORG_MANAGER/ORG_OWNER — the same roles DOCUMENT:REVIEW already requires) still need to
     * see everyone's documents to do their job, so they're exempted the same way an admin is.
     */
    private Document findForContext(UUID id, PlatformSecurityContext ctx) {
        if (ctx.isPlatformAdmin()) {
            return repository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Document", id.toString()));
        }
        Document doc = repository.findByIdAndTenantId(id, ctx.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", id.toString()));
        boolean isOwner = doc.getUserId() != null && doc.getUserId().equals(ctx.getUserId());
        boolean isReviewer = ctx.hasAnyRole("ORG_MANAGER", "ORG_OWNER");
        if (!isOwner && !isReviewer) {
            // 404, not 403 — a colleague's document shouldn't be disclosed to exist by id.
            throw new ResourceNotFoundException("Document", id.toString());
        }
        return doc;
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

        // Content-Type and extension are both entirely client-supplied — a renamed executable
        // sent with Content-Type: application/pdf and a .pdf name passed both checks above with
        // nothing to actually verify the bytes are a PDF. A magic-byte sniff closes that.
        if (!matchesDeclaredType(file, contentType.toLowerCase())) {
            throw new com.lagu.platform.common.exception.ValidationException(
                    "File content does not match its declared type (" + contentType + ")");
        }
    }

    private static final Map<String, byte[]> MAGIC_BYTES = Map.of(
            "application/pdf", new byte[]{'%', 'P', 'D', 'F'},
            "image/png",       new byte[]{(byte) 0x89, 'P', 'N', 'G'},
            "image/jpeg",      new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
    );

    /** WEBP has no fixed single signature check here (RIFF....WEBP, non-contiguous) — matched
     *  separately rather than forcing it into the simple prefix table above. */
    private boolean matchesDeclaredType(MultipartFile file, String contentType) {
        byte[] header;
        try (var in = file.getInputStream()) {
            header = in.readNBytes(12);
        } catch (java.io.IOException e) {
            throw new com.lagu.platform.common.exception.ValidationException("Could not read uploaded file");
        }

        if ("image/webp".equals(contentType)) {
            return header.length >= 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        }

        byte[] expected = MAGIC_BYTES.get(contentType);
        if (expected == null) return false; // unreachable given ALLOWED_CONTENT_TYPES, fail closed
        if (header.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (header[i] != expected[i]) return false;
        }
        return true;
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
