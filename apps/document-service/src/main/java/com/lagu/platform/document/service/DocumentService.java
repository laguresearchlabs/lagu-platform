package com.lagu.platform.document.service;

import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.document.domain.Document;
import com.lagu.platform.document.domain.DocumentRepository;
import com.lagu.platform.document.dto.ConfirmUploadRequest;
import com.lagu.platform.document.dto.DocumentDto;
import com.lagu.platform.document.dto.DocumentSubmissionStatusResponse;
import com.lagu.platform.document.dto.DocumentSubmissionStatusResponse.DocumentTypeStatus;
import com.lagu.platform.document.dto.UploadUrlRequest;
import com.lagu.platform.document.dto.UploadUrlResponse;
import com.lagu.platform.document.event.DocumentEventPublisher;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.storage.ImageConstraints;
import com.lagu.platform.storage.MediaIngest;
import com.lagu.platform.storage.MediaPolicy;
import com.lagu.platform.storage.PresignedUpload;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DocumentService {

    private final DocumentRepository     repository;
    private final StorageService         storage;
    private final StorageProperties      storageProperties;
    private final DocumentEventPublisher publisher;
    private final DocumentTypeRegistry   docTypeRegistry;
    private final MediaIngest            mediaIngest;

    /**
     * Step 1: authorize the upload and hand back a presigned PUT.
     *
     * <p>No document row is created here. The client uploads straight to the bucket, so until
     * {@link #confirmUpload} runs there is nothing to record — and a client that abandons the
     * upload leaves only an orphaned object, not a document pointing at bytes that never arrived.
     */
    public UploadUrlResponse requestUploadUrl(UploadUrlRequest request) {
        PlatformSecurityContext ctx = requireContext();

        validateDocumentType(request.getDocumentType(), request.getIdentitySubType(),
                request.getListingType());
        docTypeRegistry.policyFor(request.getDocumentType())
                .checkDeclared(request.getFileName(), request.getContentType(), request.getSizeBytes());

        String key = StorageKeys.buildPending(storageProperties.getDomain(), ctx.getUserId(),
                request.getFileName());
        PresignedUpload upload = storage.presignUpload(key, request.getContentType().toLowerCase(),
                storageProperties.getUploadUrlTtl());

        return UploadUrlResponse.builder()
                .uploadUrl(upload.url())
                .key(upload.key())
                .contentType(upload.contentType())
                .expiresAt(upload.expiresAt())
                .build();
    }

    /**
     * Step 3: the bytes are in the bucket — verify them and create the document row.
     *
     * <p>This is where validation actually bites. Step 1 only saw what the client claimed; here
     * the object's real size comes from the bucket and its leading bytes are sniffed against the
     * declared type. Under the old multipart flow that sniff ran on the request body; with
     * direct-to-bucket uploads it has to happen against stored bytes or it does not happen at all.
     */
    @Transactional
    public DocumentDto confirmUpload(ConfirmUploadRequest request) {
        PlatformSecurityContext ctx = requireContext();

        validateDocumentType(request.getDocumentType(), request.getIdentitySubType(),
                request.getListingType());
        MediaPolicy policy = docTypeRegistry.policyFor(request.getDocumentType());

        String key = request.getKey();
        // The key embeds the uploader's id, so this both confirms the key came from an
        // upload-url call and stops a caller confirming an object uploaded by someone else.
        if (!StorageKeys.isOwnedBy(key, storageProperties.getDomain(), ctx.getUserId())) {
            throw new com.lagu.platform.common.exception.ValidationException(
                    "Key does not belong to this user");
        }

        // Verifies the object, scans it for malware, and promotes it out of pending/ to its
        // durable key. No derivatives: an identity document is reviewed at full size by a human
        // and never rendered as a tile, so a thumbnail would be an extra copy of a scan of
        // someone's passport for no benefit.
        MediaIngest.Result ingested = mediaIngest.confirm(MediaIngest.Request.builder()
                .pendingKey(key)
                .policy(policy)
                .image(ImageConstraints.NONE)
                .derivatives(false)
                .build());

        Document doc = new Document();
        doc.setTenantId(ctx.getTenantId());
        doc.setUserId(ctx.getUserId());
        doc.setDocumentType(request.getDocumentType().toUpperCase());
        doc.setIdentitySubType(request.getIdentitySubType() != null
                ? request.getIdentitySubType().toUpperCase() : null);
        doc.setFileName(StorageKeys.sanitizeFileName(request.getFileName()));
        doc.setFileKey(ingested.key());
        doc.setMimeType(ingested.contentType());
        doc.setFileSizeBytes(ingested.sizeBytes());
        doc.setExpiryDate(request.getExpiryDate());
        doc.setStatus("UPLOADED");

        Document saved = repository.save(doc);
        publisher.publish(saved, "DOCUMENT_UPLOADED");
        return toDto(saved);
    }

    public List<DocumentDto> listMyDocuments() {
        PlatformSecurityContext ctx = requireContext();
        return repository.findByUserIdAndTenantIdOrderByUploadedAtDesc(ctx.getUserId(), ctx.getTenantId())
                .stream().map(this::toDto).toList();
    }

    public DocumentDto getById(UUID id) {
        PlatformSecurityContext ctx = requireContext();
        return toDto(findForContext(id, ctx));
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
        return PageResult.from(paged.map(this::toDto));
    }

    /** Platform-admin: every document for one org, regardless of uploader — the KYC review panel
     *  on a vendor's admin detail page. Caller must be authorized by the controller first. */
    public List<DocumentDto> listForTenantAdmin(UUID tenantId) {
        return repository.findByTenantIdOrderByUploadedAtDesc(tenantId)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public DocumentDto startReview(UUID id) {
        PlatformSecurityContext ctx = requireContext();
        Document doc = findForContext(id, ctx);
        doc.setStatus("UNDER_REVIEW");
        doc.setReviewedBy(ctx.getUserId());
        return toDto(repository.save(doc));
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
        return toDto(saved);
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
        return toDto(saved);
    }

    /**
     * Removes a document and the file behind it, permanently.
     *
     * <p>Hard delete, by decision: a data-subject erasure request has to actually erase, and a
     * row flagged deleted while the passport scan stays in the bucket does not do that.
     *
     * <p>Two consequences worth being explicit about, since they are the cost of that choice.
     * A VERIFIED document can be deleted, so the evidence behind a completed KYC check can
     * disappear while the vendor stays verified. And the object goes immediately rather than
     * ageing out, so an accidental delete is not recoverable. The {@code DOCUMENT_DELETED} event
     * below is what remains: it records that the document existed, its type, and who removed it,
     * without keeping the file itself.
     *
     * <p>Ordering matters. The row is removed first and the object second: a failed storage
     * delete then leaves an orphaned object, which the bucket lifecycle rule and an admin can
     * both deal with. The other order would leave a document row pointing at bytes that are
     * already gone — a broken record that every read would trip over.
     */
    @Transactional
    public void delete(UUID id) {
        PlatformSecurityContext ctx = requireContext();
        Document doc = findForContext(id, ctx);

        if (!canDelete(doc, ctx)) {
            // 404 rather than 403, matching findForContext: whether a document exists is itself
            // something a non-owner should not learn.
            throw new ResourceNotFoundException("Document", id.toString());
        }

        String fileKey = doc.getFileKey();
        repository.delete(doc);
        publisher.publish(doc, "DOCUMENT_DELETED");

        try {
            storage.delete(fileKey);
        } catch (RuntimeException e) {
            // Not fatal, and deliberately so — the document is already gone as far as every
            // caller is concerned. Logged loudly because the object is now unreferenced and only
            // this line says so.
            log.error("Deleted document {} but could not remove its object {}: {}",
                    id, fileKey, e.toString());
        }
        log.info("Deleted document {} ({}) by user {}", id, doc.getDocumentType(), ctx.getUserId());
    }

    /**
     * Owner or admin, per the agreed policy.
     *
     * <p>Reviewers are deliberately excluded even though {@code findForContext} lets them read
     * every document in their org: reading a colleague's identity document to verify it is the
     * job, destroying it is not. A platform admin can, for erasure requests.
     */
    private boolean canDelete(Document doc, PlatformSecurityContext ctx) {
        if (ctx.isPlatformAdmin()) return true;
        return doc.getUserId() != null && doc.getUserId().equals(ctx.getUserId());
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

    /**
     * Maps to the API shape, signing a fresh download URL from the stored key.
     *
     * <p>{@code fileUrl} stays in the response for clients, but it is now generated per request
     * and short-lived rather than being a value read back out of the database — which is the
     * whole point of storing keys instead of URLs.
     */
    private DocumentDto toDto(Document d) {
        return DocumentDto.from(d,
                storage.presignDownload(d.getFileKey(), storageProperties.getDownloadUrlTtl()));
    }

    private void validateDocumentType(String documentType, String identitySubType, String listingType) {
        Set<String> validTypes = docTypeRegistry.validCodes(listingType);

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
