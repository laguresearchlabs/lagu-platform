package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.dto.SearchDefinitionRequest;
import com.lagu.platform.schema.dto.SearchDefinitionResponse;
import com.lagu.platform.schema.service.SearchDefinitionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search-definitions")
@RequiredArgsConstructor
public class SearchDefinitionController {

    private final SearchDefinitionService searchDefinitionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SearchDefinitionResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(searchDefinitionService.list()));
    }

    @GetMapping("/{listingType}")
    public ResponseEntity<ApiResponse<SearchDefinitionResponse>> getByListingType(
            @PathVariable String listingType) {
        return ResponseEntity.ok(ApiResponse.ok(searchDefinitionService.getByListingType(listingType)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SearchDefinitionResponse>> upsert(
            @Valid @RequestBody SearchDefinitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(searchDefinitionService.upsert(req)));
    }

    @DeleteMapping("/{listingType}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String listingType) {
        searchDefinitionService.delete(listingType);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
