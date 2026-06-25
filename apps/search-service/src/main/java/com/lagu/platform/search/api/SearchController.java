package com.lagu.platform.search.api;

import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.search.dto.SearchRequest;
import com.lagu.platform.search.dto.SearchResponse;
import com.lagu.platform.search.service.SearchService;
import com.lagu.platform.search.service.SuggestService;
import com.lagu.platform.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
        String orgId = GatewayHeaderFilter.current().getOrgId().toString();
        return searchService.search(req, orgId);
    }

    @GetMapping("/suggest")
    @Operation(summary = "Typeahead suggestions for a given field prefix")
    @RequirePermission(resource = "RECORD", action = "READ")
    public List<String> suggest(
            @RequestParam String objectType,
            @RequestParam String field,
            @RequestParam String prefix) throws IOException {
        String orgId = GatewayHeaderFilter.current().getOrgId().toString();
        return suggestService.suggest(objectType, field, prefix, orgId);
    }
}
