package com.lagu.platform.metadata.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CategoryDto {
    private UUID id;
    private UUID parentId;
    private String objectType;
    private String slug;
    private String label;
    private String description;
    private String iconUrl;
    private int displayOrder;
    private List<CategoryDto> children;
}
