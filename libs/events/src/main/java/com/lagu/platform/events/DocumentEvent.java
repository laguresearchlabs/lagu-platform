package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentEvent implements PlatformEvent {

    /**
     * DOCUMENT_UPLOADED | DOCUMENT_VERIFIED | DOCUMENT_REJECTED | DOCUMENT_EXPIRED
     */
    private String eventType;

    private UUID   documentId;
    private UUID   userId;
    private UUID   orgId;

    /** RESUME | IDENTITY_PROOF | PHOTOGRAPH | ACADEMIC_CERTIFICATE | ADDRESS_PROOF | OTHER */
    private String documentType;

    /** AADHAAR | PASSPORT | DRIVING_LICENSE | VOTER_ID | PAN_CARD — non-null only for IDENTITY_PROOF */
    private String identitySubType;

    /** UPLOADED | UNDER_REVIEW | VERIFIED | REJECTED | EXPIRED */
    private String status;

    private String rejectionReason;
    private String fileName;

    private Instant occurredAt;
}
