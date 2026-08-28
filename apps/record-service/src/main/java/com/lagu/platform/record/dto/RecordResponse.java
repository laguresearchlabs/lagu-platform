package com.lagu.platform.record.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordResponse {

    private UUID   id;
    private UUID   tenantId;
    private String objectType;
    /** Schema version this record was authored against (ADR-11). */
    private int    schemaVersion;
    private String status;
    private Map<String, Object> data;
    private UUID   createdBy;
    private UUID   updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /**
     * Set only on the response to an edit that was held for review rather than applied, alongside
     * HTTP 202. The record fields above are the *current* stored values — deliberately not the
     * proposed ones — so a client that ignores this field still shows the truth rather than
     * displaying an edit as though it were live.
     */
    private UUID pendingChangeSetId;

    // Populated when record has a verification entry
    private String verificationTier;
    private String verificationStatus;
    private OffsetDateTime verificationExpiresAt;
}
