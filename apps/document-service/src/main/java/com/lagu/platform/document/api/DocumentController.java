package com.lagu.platform.document.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.document.dto.DocumentDto;
import com.lagu.platform.document.dto.DocumentReviewRequest;
import com.lagu.platform.document.dto.DocumentSubmissionStatusResponse;
import com.lagu.platform.document.service.DocumentService;
import com.lagu.platform.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;

    /**
     * Upload a document. Accepts multipart/form-data.
     *
     * Required params: file, documentType
     * Optional params: identitySubType (required when documentType=IDENTITY_PROOF),
     *                  expiryDate (ISO date, e.g. 2030-01-15)
     *
     * Document types: RESUME | IDENTITY_PROOF | PHOTOGRAPH |
     *                 ACADEMIC_CERTIFICATE | ADDRESS_PROOF | OTHER
     *
     * Identity sub-types: AADHAAR | PASSPORT | DRIVING_LICENSE | VOTER_ID | PAN_CARD
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(resource = "DOCUMENT", action = "CREATE")
    public ResponseEntity<ApiResponse<DocumentDto>> upload(
            @RequestParam("file")                                         MultipartFile file,
            @RequestParam("documentType")                                 String documentType,
            @RequestParam(value = "identitySubType", required = false)    String identitySubType,
            @RequestParam(value = "expiryDate",      required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)                LocalDate expiryDate) {

        DocumentDto dto = service.upload(file, documentType, identitySubType, expiryDate);
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
