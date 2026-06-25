package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateRelationshipRequest {

    @NotBlank
    private String relationshipName;

    @NotNull
    private UUID targetRecordId;
}
