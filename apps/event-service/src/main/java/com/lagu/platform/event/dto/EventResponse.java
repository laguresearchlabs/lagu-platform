package com.lagu.platform.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventResponse {
    private UUID id;
    private UUID recordId;
    private String objectType;
    private UUID ownerUserId;
    private String status;
    private String myRole;
    /** INVITED | ACCEPTED | DECLINED — lets the frontend show an accept/decline prompt without
     *  needing the full (ACCEPTED-only) member list just to find the viewer's own row. */
    private String myStatus;
    private Map<String, Object> data;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
