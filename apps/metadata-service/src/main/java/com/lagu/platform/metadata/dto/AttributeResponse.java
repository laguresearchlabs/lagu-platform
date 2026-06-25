package com.lagu.platform.metadata.dto;

import com.lagu.platform.metadata.domain.AttributeType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class AttributeResponse {

    private UUID   id;
    private UUID   orgId;
    private String name;
    private String label;
    private String description;
    private AttributeType attributeType;
    private boolean required;
    private boolean searchable;
    private boolean filterable;
    private boolean sortable;
    private boolean facetable;
    private boolean unique;
    private String  defaultValue;
    private Map<String, Object> validationRules;
    private List<String> enumValues;
    private Map<String, Object> config;
    private boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
