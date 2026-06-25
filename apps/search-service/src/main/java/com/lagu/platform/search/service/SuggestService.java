package com.lagu.platform.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuggestService {

    private final OpenSearchClient    osClient;
    private final IndexMappingBuilder mappingBuilder;

    /**
     * Returns distinct values for {@code field} that start with {@code prefix}.
     * Uses a prefix filter query + terms aggregation (cheap typeahead).
     */
    public List<String> suggest(String objectType, String field, String prefix, String orgId) throws IOException {
        String index = mappingBuilder.indexName(orgId, objectType);

        var response = osClient.search(r -> r
                        .index(index)
                        .size(0)
                        .query(Query.of(q -> q.prefix(p -> p.field(field).value(prefix.toLowerCase()))))
                        .aggregations("suggestions", a -> a.terms(t -> t.field(field).size(10))),
                Map.class);

        if (response.aggregations() == null) return List.of();

        return response.aggregations()
                .get("suggestions")
                .sterms()
                .buckets()
                .array()
                .stream()
                .map(b -> b.key())
                .collect(Collectors.toList());
    }
}
