package com.lagu.platform.document.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "document", schema = "documents")
@Getter
@Setter
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** RESUME | IDENTITY_PROOF | PHOTOGRAPH | ACADEMIC_CERTIFICATE | ADDRESS_PROOF | OTHER */
    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    /** AADHAAR | PASSPORT | DRIVING_LICENSE | VOTER_ID | PAN_CARD — set only for IDENTITY_PROOF */
    @Column(name = "identity_sub_type", length = 50)
    private String identitySubType;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /** UPLOADED | UNDER_REVIEW | VERIFIED | REJECTED | EXPIRED */
    @Column(nullable = false, length = 30)
    private String status = "UPLOADED";

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        uploadedAt = Instant.now();
        updatedAt  = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
