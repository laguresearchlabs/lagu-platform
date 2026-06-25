package com.lagu.platform.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SearchRequest {

    @NotBlank
    private String objectType;

    /** Full-text query string; null = match all. */
    private String query;

    /**
     * Field filters. Values are either:
     * - A plain string/number for exact match
     * - A Map with "gte"/"lte"/"gt"/"lt" for range queries
     */
    private Map<String, Object> filters;

    private List<SortCriteria> sort;

    /** Fields to aggregate for faceted counts (e.g. "data.city", "status"). */
    private List<String> facets;

    private int page = 0;
    private int size = 20;
}
