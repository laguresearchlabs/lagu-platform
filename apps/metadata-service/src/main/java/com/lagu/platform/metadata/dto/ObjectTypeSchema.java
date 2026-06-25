package com.lagu.platform.metadata.dto;

import com.lagu.platform.metadata.domain.AttributeType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ObjectTypeSchema {

    private String          objectType;
    private List<FieldSchema> fields;

    @Data
    @Builder
    public static class FieldSchema {
        private String        name;
        private String        label;
        private AttributeType type;
        private boolean       required;
        private boolean       searchable;
        private boolean       filterable;
        private boolean       sortable;
        private boolean       facetable;
        private boolean       unique;
        private List<String>  enumValues;
        private Map<String, Object> validation;
        private Map<String, Object> config;
    }
}
