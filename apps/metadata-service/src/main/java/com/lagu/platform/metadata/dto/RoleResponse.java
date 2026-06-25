package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class RoleResponse {

    private UUID                    id;
    private UUID                    orgId;
    private String                  name;
    private String                  label;
    private String                  description;
    private String                  roleLevel;
    private boolean                 active;
    private List<PermissionResponse> permissions;
    private OffsetDateTime          createdAt;
}
