package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class MemberResponse {

    private UUID           id;
    private UUID           userId;
    private String         roleName;
    private OffsetDateTime joinedAt;
}
