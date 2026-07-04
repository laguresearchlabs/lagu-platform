package com.lagu.platform.search.service;

import com.lagu.platform.search.dto.SearchRequest;
import com.lagu.platform.search.dto.SearchResponse;
import com.lagu.platform.search.dto.SortCriteria;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldSort;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.query_dsl.*;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.json.JsonData;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final OpenSearchClient    osClient;
    private final IndexMappingBuilder mappingBuilder;

    public SearchResponse search(SearchRequest req, String orgId) throws IOException {
        String index = mappingBuilder.indexName(orgId, req.getObjectType());
        return execute(index, buildQuery(req, orgId), req);
    }

    /**
     * Cross-org marketplace search over published listing snapshots. The consumer index only
     * ever contains PUBLISHED snapshots (visibility decided at publish time), so no org or
     * status filter applies; relevance is multiplied by the tier-derived {@code searchBoost}.
     */
    public SearchResponse searchConsumer(SearchRequest req) throws IOException {
        String index = mappingBuilder.consumerIndexName(req.getObjectType());

        Query boosted = Query.of(q -> q.functionScore(fs -> fs
                .query(buildQuery(req, null))
                .functions(fn -> fn.fieldValueFactor(fv -> fv.field("searchBoost").missing(1.0)))
                .boostMode(FunctionBoostMode.Multiply)
                .scoreMode(FunctionScoreMode.Multiply)));

        try {
            return execute(index, boosted, req);
        } catch (org.opensearch.client.opensearch._types.OpenSearchException e) {
            // No listing of this type has ever been published → the index doesn't exist yet.
            // An empty result page is the correct answer, not a 500.
            if ("index_not_found_exception".equals(e.error().type())) {
                return SearchResponse.builder()
                        .total(0).page(req.getPage()).size(req.getSize())
                        .results(List.of()).facets(Map.of())
                        .build();
            }
            throw e;
        }
    }

    private SearchResponse execute(String index, Query query, SearchRequest req) throws IOException {
        int from = req.getPage() * req.getSize();

        var searchBuilder = new org.opensearch.client.opensearch.core.SearchRequest.Builder()
                .index(index)
                .query(query)
                .from(from)
                .size(req.getSize());

        if (req.getSort() != null) {
            for (SortCriteria s : req.getSort()) {
                SortOrder order = "desc".equalsIgnoreCase(s.getOrder()) ? SortOrder.Desc : SortOrder.Asc;
                searchBuilder.sort(SortOptions.of(so -> so.field(FieldSort.of(f -> f.field(s.getField()).order(order)))));
            }
        }

        if (req.getFacets() != null) {
            Map<String, Aggregation> aggs = new HashMap<>();
            for (String facetField : req.getFacets()) {
                aggs.put(facetField, Aggregation.of(a -> a.terms(t -> t.field(facetField).size(20))));
            }
            searchBuilder.aggregations(aggs);
        }

        org.opensearch.client.opensearch.core.SearchResponse<Map> osResp =
                osClient.search(searchBuilder.build(), Map.class);

        return buildResponse(osResp, req);
    }

    // ── query construction ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Query buildQuery(SearchRequest req, String orgId) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        // Defense-in-depth for org-scoped search: don't rely solely on per-org index-name
        // isolation — filter every query by orgId at the document level too. Consumer search
        // passes null: its index is cross-org by design and holds only published snapshots.
        if (orgId != null) {
            bool.filter(f -> f.term(t -> t.field("orgId").value(v -> v.stringValue(orgId))));
        }

        if (req.getQuery() != null && !req.getQuery().isBlank()) {
            bool.must(m -> m.multiMatch(mm -> mm
                    .query(req.getQuery())
                    .fields(List.of("data.*"))
                    .type(TextQueryType.BestFields)));
        } else {
            bool.must(m -> m.matchAll(ma -> ma));
        }

        if (req.getFilters() != null) {
            for (Map.Entry<String, Object> entry : req.getFilters().entrySet()) {
                String field = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Map<?, ?> rawMap) {
                    Map<String, Object> rangeMap = (Map<String, Object>) rawMap;
                    bool.filter(f -> f.range(r -> {
                        var rb = r.field(field);
                        if (rangeMap.containsKey("gte")) rb.gte(JsonData.of(rangeMap.get("gte")));
                        if (rangeMap.containsKey("lte")) rb.lte(JsonData.of(rangeMap.get("lte")));
                        if (rangeMap.containsKey("gt"))  rb.gt(JsonData.of(rangeMap.get("gt")));
                        if (rangeMap.containsKey("lt"))  rb.lt(JsonData.of(rangeMap.get("lt")));
                        return rb;
                    }));
                } else {
                    String strVal = String.valueOf(value);
                    bool.filter(f -> f.term(t -> t.field(field).value(v -> v.stringValue(strVal))));
                }
            }
        }

        return Query.of(q -> q.bool(bool.build()));
    }

    // ── result mapping ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private SearchResponse buildResponse(
            org.opensearch.client.opensearch.core.SearchResponse<Map> response, SearchRequest req) {

        List<SearchResponse.SearchHitDto> hits = response.hits().hits().stream()
                .map(this::toHitDto)
                .collect(Collectors.toList());

        long total = response.hits().total() != null ? response.hits().total().value() : 0L;

        Map<String, List<SearchResponse.FacetBucket>> facets = new HashMap<>();
        if (response.aggregations() != null) {
            response.aggregations().forEach((facetName, agg) -> {
                List<StringTermsBucket> buckets = agg.sterms().buckets().array();
                List<SearchResponse.FacetBucket> result = buckets.stream()
                        .map(b -> SearchResponse.FacetBucket.builder()
                                .value(b.key())
                                .count(b.docCount())
                                .build())
                        .collect(Collectors.toList());
                facets.put(facetName, result);
            });
        }

        return SearchResponse.builder()
                .total(total)
                .page(req.getPage())
                .size(req.getSize())
                .results(hits)
                .facets(facets)
                .build();
    }

    @SuppressWarnings("unchecked")
    private SearchResponse.SearchHitDto toHitDto(Hit<Map> hit) {
        Map<String, Object> source = hit.source() != null ? hit.source() : Map.of();
        return SearchResponse.SearchHitDto.builder()
                .recordId((String)  source.get("recordId"))
                .objectType((String) source.get("objectType"))
                .status((String) source.get("status"))
                .data((Map<String, Object>) source.get("data"))
                .score(hit.score() != null ? hit.score() : 0.0)
                .build();
    }
}
