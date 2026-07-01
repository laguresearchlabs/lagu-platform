package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class DocumentTypeDto {
    private UUID id;
    private String code;
    private String label;
    private String description;
    private String objectType;
    private boolean required;
    private boolean expiryTracked;
    private List<String> allowedMimeTypes;
    private int maxSizeMb;
    private int displayOrder;
}
