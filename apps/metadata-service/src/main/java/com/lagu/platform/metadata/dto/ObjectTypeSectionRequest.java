package com.lagu.platform.metadata.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class ObjectTypeSectionRequest {

    @NotNull
    private UUID entityId;

    private String  label;
    private int     displayOrder;
    private boolean collapsible;
}
