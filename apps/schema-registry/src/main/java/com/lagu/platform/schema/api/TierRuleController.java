package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.dto.TierCheckResponse;
import com.lagu.platform.schema.dto.TierRuleRequest;
import com.lagu.platform.schema.dto.TierRuleResponse;
import com.lagu.platform.schema.service.TierCheckService;
import com.lagu.platform.schema.service.TierRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tier-rules")
@RequiredArgsConstructor
public class TierRuleController {

    private final TierRuleService tierRuleService;
    private final TierCheckService tierCheckService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TierRuleResponse>>> list(
            @RequestParam(required = false) String listingType,
            @RequestParam(required = false) String tier) {
        return ResponseEntity.ok(ApiResponse.ok(tierRuleService.list(listingType, tier)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TierRuleResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(tierRuleService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TierRuleResponse>> create(
            @Valid @RequestBody TierRuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(tierRuleService.create(req)));
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<ApiResponse<TierRuleResponse>> toggleActive(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(tierRuleService.toggleActive(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        tierRuleService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/check")
    public ResponseEntity<ApiResponse<TierCheckResponse>> check(
            @RequestParam String recordId,
            @RequestParam String targetTier,
            @RequestParam String listingType) {
        return ResponseEntity.ok(ApiResponse.ok(tierCheckService.check(recordId, targetTier, listingType)));
    }
}
