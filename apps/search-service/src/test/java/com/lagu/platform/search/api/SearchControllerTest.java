package com.lagu.platform.search.api;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.search.dto.SearchRequest;
import com.lagu.platform.search.service.SearchService;
import com.lagu.platform.search.service.SuggestService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Regression coverage for the review's finding: search()/suggest() called
 * GatewayHeaderFilter.current().getOrgId().toString() unguarded — an internal SVC_* caller
 * (permitted READ with no X-Org-Id header) got a raw NullPointerException (500) instead of a
 * clean, identifiable error.
 */
class SearchControllerTest {

    private final SearchService searchService = mock(SearchService.class);
    private final SuggestService suggestService = mock(SuggestService.class);
    private final SearchController controller = new SearchController(searchService, suggestService);

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    private void asCaller(PlatformSecurityContext ctx) {
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(ctx);
    }

    @AfterEach
    void tearDown() {
        if (gatewayMock != null) gatewayMock.close();
    }

    @Test
    void searchWithNoOrgContextThrowsCleanErrorNotNpe() {
        asCaller(PlatformSecurityContext.builder()
                .roles(Set.of("SVC_AUTOMATION_SERVICE")).orgId(null).build());

        SearchRequest req = new SearchRequest();
        req.setObjectType("VENUE");

        assertThatThrownBy(() -> controller.search(req))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("organization context");
    }

    @Test
    void searchWithNullContextThrowsCleanErrorNotNpe() {
        asCaller(null);
        SearchRequest req = new SearchRequest();
        req.setObjectType("VENUE");

        assertThatThrownBy(() -> controller.search(req)).isInstanceOf(PlatformException.class);
    }

    @Test
    void suggestWithNoOrgContextThrowsCleanErrorNotNpe() {
        asCaller(PlatformSecurityContext.builder().roles(Set.of("SVC_X")).orgId(null).build());

        assertThatThrownBy(() -> controller.suggest("VENUE", "name", "Grand"))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void searchWithValidOrgContextDelegatesToService() throws Exception {
        UUID orgId = UUID.randomUUID();
        asCaller(PlatformSecurityContext.builder().userId(UUID.randomUUID()).orgId(orgId)
                .roles(Set.of("ORG_MANAGER")).build());

        SearchRequest req = new SearchRequest();
        req.setObjectType("VENUE");

        controller.search(req);

        Mockito.verify(searchService).search(req, orgId.toString());
    }
}
