package com.lagu.platform.metadata.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class EntityAttributeRequest {

    @NotNull
    private UUID attributeId;

    private int     displayOrder;
    private boolean required;
}
