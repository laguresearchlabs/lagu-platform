package com.lagu.platform.search.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.search.dto.SearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a marketplace search is allowed to span.
 *
 * <p>{@code objectType} was {@code @NotBlank}, so every consumer search had to name one listing
 * type. That made a single search box across the whole marketplace impossible to express: a
 * shopper had to decide whether the thing they wanted was a venue or a caterer before typing,
 * which is a question they often cannot answer and never before typing. events-ui kept category
 * tiles as its only entry point for exactly this reason.
 *
 * <p>The type never appeared in the query body — it only ever picked the index — so searching
 * every type is a wildcard across the one-index-per-type layout, and these tests pin the index
 * selection, which is the whole of the change.
 */
class ConsumerSearchScopeTest {

    private OpenSearchClient osClient;
    private IndexMappingBuilder mappingBuilder;
    private SearchService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        osClient = mock(OpenSearchClient.class);
        mappingBuilder = mock(IndexMappingBuilder.class);
        service = new SearchService(osClient, mappingBuilder);

        when(mappingBuilder.consumerIndexPattern()).thenReturn("platform-consumer-*");
        when(mappingBuilder.consumerIndexName("VENUE")).thenReturn("platform-consumer-venue");

        SearchResponse<Map> empty = mock(SearchResponse.class);
        org.opensearch.client.opensearch.core.search.HitsMetadata<Map> hits =
                mock(org.opensearch.client.opensearch.core.search.HitsMetadata.class);
        when(hits.hits()).thenReturn(List.of());
        when(hits.total()).thenReturn(null);
        when(empty.hits()).thenReturn(hits);
        when(empty.aggregations()).thenReturn(Map.of());
        when(osClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(Map.class)))
                .thenReturn(empty);
    }

    private String indexSearched() throws IOException {
        ArgumentCaptor<org.opensearch.client.opensearch.core.SearchRequest> captor =
                ArgumentCaptor.forClass(org.opensearch.client.opensearch.core.SearchRequest.class);
        org.mockito.Mockito.verify(osClient).search(captor.capture(), eq(Map.class));
        return String.join(",", captor.getValue().index());
    }

    @Test
    void aNamedTypeSearchesThatTypesIndexAlone() throws IOException {
        SearchRequest req = new SearchRequest();
        req.setObjectType("VENUE");

        service.searchConsumer(req);

        assertThat(indexSearched()).isEqualTo("platform-consumer-venue");
    }

    @Test
    void noTypeSearchesEveryPublishedListingType() throws IOException {
        SearchRequest req = new SearchRequest();

        service.searchConsumer(req);

        // One index per type, so "everything" is a wildcard across them.
        assertThat(indexSearched()).isEqualTo("platform-consumer-*");
    }

    @Test
    void aBlankTypeIsTreatedAsNoTypeRatherThanAnIndexNamedNothing() throws IOException {
        SearchRequest req = new SearchRequest();
        req.setObjectType("   ");

        service.searchConsumer(req);

        assertThat(indexSearched()).isEqualTo("platform-consumer-*");
    }

    /**
     * The guard that moved off the DTO. Dropping {@code @NotBlank} is what lets the marketplace
     * ask for everything; without this check it would also have let an org-scoped caller reach
     * {@code indexName(tenant, null)} and get whatever that produced.
     */
    @Test
    void orgScopedSearchStillRequiresAType() {
        SearchRequest req = new SearchRequest();

        assertThatThrownBy(() -> service.search(req, "tenant-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("objectType is required");

        req.setObjectType("  ");
        assertThatThrownBy(() -> service.search(req, "tenant-1"))
                .isInstanceOf(ValidationException.class);
    }
}
