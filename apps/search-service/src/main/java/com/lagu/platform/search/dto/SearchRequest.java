package com.lagu.platform.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SearchRequest {

    /**
     * Which listing type to search.
     *
     * <p>Required for org-scoped search, where it selects the org's index — {@code SearchService
     * .search} rejects a blank one, because there is no such thing as "every index in this org"
     * that a caller should get by accident.
     *
     * <p><b>Optional for consumer search</b>, where blank means every published listing type at
     * once. That is the difference between a marketplace with a search box and one where a
     * shopper must first decide whether the thing they want is a venue or a caterer — a question
     * they often cannot answer, and never before typing.
     */
    private String objectType;

    /** Full-text query string; null = match all. */
    private String query;

    /**
     * Only listings that can take this day, as an ISO date.
     *
     * <p>Meaningful for consumer search, where availability is indexed. Harmless on an org-scoped
     * search: the field is unmapped there, so the exclusion matches nothing and narrows nothing.
     *
     * <p>A field of its own rather than an entry in {@link #filters}, because the filter map
     * expresses equality and ranges and this is a negation — "not among the days this listing has
     * taken". Bending the map to carry a `not` would make every other filter's meaning less
     * obvious to serve one case, and "available on" is a first-class thing to ask a marketplace
     * anyway.
     */
    private String availableOn;

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
