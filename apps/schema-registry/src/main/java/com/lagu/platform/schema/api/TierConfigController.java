package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.domain.TierConfiguration;
import com.lagu.platform.schema.dto.TierConfigRequest;
import com.lagu.platform.schema.service.TierConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tier-configs")
@RequiredArgsConstructor
public class TierConfigController {

    private final TierConfigService tierConfigService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TierConfiguration>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(tierConfigService.list()));
    }

    @GetMapping("/{tierName}")
    public ResponseEntity<ApiResponse<TierConfiguration>> getByTierName(
            @PathVariable String tierName,
            @RequestParam(required = false) String listingType) {
        return ResponseEntity.ok(ApiResponse.ok(tierConfigService.getByTierName(tierName, listingType)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TierConfiguration>> create(
            @Valid @RequestBody TierConfigRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(tierConfigService.create(req)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TierConfiguration>> update(
            @PathVariable UUID id,
            @Valid @RequestBody TierConfigRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(tierConfigService.update(id, req)));
    }
}
