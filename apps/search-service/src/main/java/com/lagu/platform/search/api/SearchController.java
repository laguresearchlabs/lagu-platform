package com.lagu.platform.search.api;

import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.search.dto.SearchRequest;
import com.lagu.platform.search.dto.SearchResponse;
import com.lagu.platform.search.service.SearchService;
import com.lagu.platform.search.service.SuggestService;
import com.lagu.platform.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Tag(name = "Search", description = "Full-text search across platform records")
public class SearchController {

    private final SearchService   searchService;
    private final SuggestService  suggestService;

    @PostMapping
    @Operation(summary = "Search records with full-text query, filters, sort, and facets")
    @RequirePermission(resource = "RECORD", action = "READ")
    public SearchResponse search(@RequestBody @Valid SearchRequest req) throws IOException {
        return searchService.search(req, requireOrgId().toString());
    }

    /**
     * Consumer/marketplace search across all vendors' PUBLISHED listings. Public — listed in
     * platform.security.public-paths — because the index only ever contains data the workflow
     * already approved for consumer visibility; ranking multiplies relevance by the vendor's
     * verification-tier searchBoost.
     */
    @PostMapping("/consumer")
    @Operation(summary = "Public marketplace search over published listings, tier-boosted")
    public SearchResponse consumerSearch(@RequestBody @Valid SearchRequest req) throws IOException {
        return searchService.searchConsumer(req);
    }

    @GetMapping("/suggest")
    @Operation(summary = "Typeahead suggestions for a given field prefix")
    @RequirePermission(resource = "RECORD", action = "READ")
    public List<String> suggest(
            @RequestParam String objectType,
            @RequestParam String field,
            @RequestParam String prefix) throws IOException {
        return suggestService.suggest(objectType, field, prefix, requireOrgId().toString());
    }

    /**
     * Both org-scoped endpoints previously did {@code GatewayHeaderFilter.current().getOrgId()
     * .toString()} unguarded — an internal SVC_* caller (permitted READ with no X-Org-Id header;
     * see DefaultPermissionEvaluator) hit a raw NullPointerException (500) instead of a clean
     * error identifying the actual problem.
     */
    private java.util.UUID requireOrgId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getOrgId() == null) {
            throw new PlatformException("ORG_CONTEXT_REQUIRED",
                    "An organization context is required for this search", HttpStatus.FORBIDDEN);
        }
        return ctx.getOrgId();
    }
}
