package com.lagu.platform.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(max = 50, message = "at most 50 filters")
    private Map<String, Object> filters;

    @Size(max = 10, message = "at most 10 sort criteria")
    private List<SortCriteria> sort;

    /** Fields to aggregate for faceted counts (e.g. "data.city", "status"). Capped: an
     *  unauthenticated caller (consumerSearch is public) could otherwise request an unbounded
     *  number of terms aggregations in one request — each one a real OpenSearch computation. */
    @Size(max = 10, message = "at most 10 facets")
    private List<String> facets;

    @Min(value = 0, message = "page must be >= 0")
    private int page = 0;

    // Also caps how deep OpenSearch's `from` parameter (page * size) can go via the request
    // itself; unauthenticated deep-pagination scans are still possible up to page * 100 but no
    // longer unbounded.
    @Min(value = 1, message = "size must be >= 1")
    @Max(value = 100, message = "size must be <= 100")
    private int size = 20;
}
