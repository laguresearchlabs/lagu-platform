package com.lagu.platform.metadata.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AddMemberRequest {

    @NotNull
    private UUID userId;

    private String roleName;
}
