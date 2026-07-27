package com.lagu.platform.document.dto;

import com.lagu.platform.document.domain.Document;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class DocumentDto {

    private UUID    id;
    private UUID    tenantId;
    private UUID    userId;
    private String  documentType;
    private String  identitySubType;
    private String  fileName;
    private String  fileUrl;
    private String  mimeType;
    private Long    fileSizeBytes;
    private String  status;
    private String  rejectionReason;
    private UUID    reviewedBy;
    private OffsetDateTime reviewedAt;
    private LocalDate expiryDate;
    private Map<String, Object> metadata;
    private OffsetDateTime uploadedAt;
    private OffsetDateTime updatedAt;

    public static DocumentDto from(Document d) {
        return DocumentDto.builder()
                .id(d.getId())
                .tenantId(d.getTenantId())
                .userId(d.getUserId())
                .documentType(d.getDocumentType())
                .identitySubType(d.getIdentitySubType())
                .fileName(d.getFileName())
                .fileUrl(d.getFileUrl())
                .mimeType(d.getMimeType())
                .fileSizeBytes(d.getFileSizeBytes())
                .status(d.getStatus())
                .rejectionReason(d.getRejectionReason())
                .reviewedBy(d.getReviewedBy())
                .reviewedAt(d.getReviewedAt() != null ? d.getReviewedAt().atOffset(ZoneOffset.UTC) : null)
                .expiryDate(d.getExpiryDate())
                .metadata(d.getMetadata())
                .uploadedAt(d.getUploadedAt() != null ? d.getUploadedAt().atOffset(ZoneOffset.UTC) : null)
                .updatedAt(d.getUpdatedAt() != null ? d.getUpdatedAt().atOffset(ZoneOffset.UTC) : null)
                .build();
    }
}
