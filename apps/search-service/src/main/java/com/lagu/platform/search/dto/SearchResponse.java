package com.lagu.platform.search.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class SearchResponse {

    private long                       total;
    private int                        page;
    private int                        size;
    private List<SearchHitDto>         results;
    private Map<String, List<FacetBucket>> facets;

    @Data
    @Builder
    public static class SearchHitDto {
        private String              recordId;
        private String              objectType;
        private String              status;
        private Map<String, Object> data;
        private double              score;
    }

    @Data
    @Builder
    public static class FacetBucket {
        private String value;
        private long   count;
    }
}
