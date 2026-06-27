package com.lagu.platform.document.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class DocumentSubmissionStatusResponse {

    private List<DocumentTypeStatus> documents;
    private boolean allRequiredSubmitted;
    private boolean allRequiredVerified;

    @Data
    @Builder
    public static class DocumentTypeStatus {
        private String  documentType;
        private String  label;
        private boolean required;
        /** MISSING | UPLOADED | UNDER_REVIEW | VERIFIED | REJECTED | EXPIRED */
        private String  status;
        private UUID    documentId;
        private String  identitySubType;
        private String  rejectionReason;
        private Instant uploadedAt;
    }
}
