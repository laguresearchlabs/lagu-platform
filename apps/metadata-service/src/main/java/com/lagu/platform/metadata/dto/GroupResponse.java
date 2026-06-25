package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class GroupResponse {

    private UUID              id;
    private UUID              orgId;
    private String            name;
    private String            description;
    private boolean           active;
    private List<MemberResponse> members;
    private OffsetDateTime    createdAt;
    private OffsetDateTime    updatedAt;
}
