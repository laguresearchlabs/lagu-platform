package com.lagu.platform.document.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.document.dto.ConfirmUploadRequest;
import com.lagu.platform.document.dto.DocumentDto;
import com.lagu.platform.document.dto.DocumentReviewRequest;
import com.lagu.platform.document.dto.DocumentSubmissionStatusResponse;
import com.lagu.platform.document.dto.UploadUrlRequest;
import com.lagu.platform.document.dto.UploadUrlResponse;
import com.lagu.platform.document.service.DocumentService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;

    /**
     * Step 1 of 3 — request a presigned upload URL.
     *
     * <p>Uploads go straight from the client to the storage bucket; bytes never pass through
     * this service. The flow is:
     * <ol>
     *   <li>{@code POST /upload-url} — returns {@code uploadUrl} and {@code key}</li>
     *   <li>{@code PUT} the file to {@code uploadUrl} with the {@code Content-Type} header set
     *       to the {@code contentType} in the response (it is bound into the signature)</li>
     *   <li>{@code POST /confirm} with the {@code key} — creates the document record</li>
     * </ol>
     *
     * <p>Valid documentType values depend on listingType — see GET /submission-status or
     * schema-registry's GET /api/v1/document-requirements/catalog for the full set.
     * Identity sub-types: AADHAAR | PASSPORT | DRIVING_LICENSE | VOTER_ID | PAN_CARD
     */
    @PostMapping("/upload-url")
    @RequirePermission(resource = "DOCUMENT", action = "CREATE")
    public ResponseEntity<ApiResponse<UploadUrlResponse>> requestUploadUrl(
            @Valid @RequestBody UploadUrlRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.requestUploadUrl(request)));
    }

    /**
     * Step 3 of 3 — confirm the upload landed and create the document record.
     *
     * <p>Verifies the object exists, is within the size limit, and that its leading bytes match
     * the declared content type before anything is persisted. Rejects (and deletes) the object
     * otherwise.
     */
    @PostMapping("/confirm")
    @RequirePermission(resource = "DOCUMENT", action = "CREATE")
    public ResponseEntity<ApiResponse<DocumentDto>> confirmUpload(
            @Valid @RequestBody ConfirmUploadRequest request) {
        DocumentDto dto = service.confirmUpload(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(dto));
    }

    /** List all documents submitted by the authenticated user. */
    @GetMapping
    @RequirePermission(resource = "DOCUMENT", action = "READ")
    public ResponseEntity<ApiResponse<List<DocumentDto>>> listMyDocuments() {
        return ResponseEntity.ok(ApiResponse.ok(service.listMyDocuments()));
    }

    /** Get a single document by ID. */
    @GetMapping("/{id}")
    @RequirePermission(resource = "DOCUMENT", action = "READ")
    public ResponseEntity<ApiResponse<DocumentDto>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    /**
     * Returns the submission checklist: which document types are required/optional,
     * and the current status of each (MISSING, UPLOADED, UNDER_REVIEW, VERIFIED, REJECTED, EXPIRED).
     */
    @GetMapping("/submission-status")
    @RequirePermission(resource = "DOCUMENT", action = "READ")
    public ResponseEntity<ApiResponse<DocumentSubmissionStatusResponse>> submissionStatus() {
        return ResponseEntity.ok(ApiResponse.ok(service.getSubmissionStatus()));
    }

    /** Platform-admin: every document for one org (any uploader, any status) — powers the KYC
     *  panel on a vendor's admin detail page. */
    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<List<DocumentDto>>> listForTenantAdmin(@RequestParam UUID tenantId) {
        requirePlatformAdmin();
        return ResponseEntity.ok(ApiResponse.ok(service.listForTenantAdmin(tenantId)));
    }

    private void requirePlatformAdmin() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!ctx.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin role required");
        }
    }

    // ── HR review endpoints (ORG_MANAGER / ORG_OWNER) ─────────────────────────

    /** List documents awaiting review (status = UPLOADED), oldest first. */
    @GetMapping("/pending-review")
    @RequirePermission(resource = "DOCUMENT", action = "REVIEW")
    public ResponseEntity<ApiResponse<PageResult<DocumentDto>>> pendingReview(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.getPendingReview(page, size)));
    }

    /** Move a document to UNDER_REVIEW (claim it for review). */
    @PostMapping("/{id}/review")
    @RequirePermission(resource = "DOCUMENT", action = "REVIEW")
    public ResponseEntity<ApiResponse<DocumentDto>> startReview(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.startReview(id)));
    }

    /** Mark a document as VERIFIED. */
    @PostMapping("/{id}/verify")
    @RequirePermission(resource = "DOCUMENT", action = "REVIEW")
    public ResponseEntity<ApiResponse<DocumentDto>> verify(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.verify(id)));
    }

    /** Reject a document with an optional reason. */
    @PostMapping("/{id}/reject")
    @RequirePermission(resource = "DOCUMENT", action = "REVIEW")
    public ResponseEntity<ApiResponse<DocumentDto>> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) DocumentReviewRequest req) {
        String reason = req != null ? req.getRejectionReason() : null;
        return ResponseEntity.ok(ApiResponse.ok(service.reject(id, reason)));
    }
}
