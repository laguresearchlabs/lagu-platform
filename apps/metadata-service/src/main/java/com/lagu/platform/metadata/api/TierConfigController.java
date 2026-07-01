package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.domain.TierConfiguration;
import com.lagu.platform.metadata.domain.TierConfigurationRepository;
import com.lagu.platform.metadata.dto.TierConfigDto;
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

    private final TierConfigurationRepository repository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TierConfigDto>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(
                repository.findByActiveTrue().stream().map(this::toDto).toList()));
    }

    @GetMapping("/{tierName}")
    public ResponseEntity<ApiResponse<TierConfigDto>> getByTierName(
            @PathVariable String tierName,
            @RequestParam(required = false) String objectType) {
        List<TierConfiguration> matches = objectType != null
                ? repository.findByTierNameForObjectType(tierName.toUpperCase(), objectType.toUpperCase())
                : repository.findByTierNameAndObjectTypeIsNull(tierName.toUpperCase())
                        .map(List::of).orElse(List.of());
        return matches.isEmpty()
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(ApiResponse.ok(toDto(matches.get(0))));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TierConfigDto>> create(@RequestBody TierConfiguration req) {
        TierConfiguration saved = repository.save(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toDto(saved)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TierConfigDto>> update(
            @PathVariable UUID id, @RequestBody TierConfiguration req) {
        return repository.findById(id).map(existing -> {
            req.setId(id);
            req.setCreatedAt(existing.getCreatedAt());
            return ResponseEntity.ok(ApiResponse.ok(toDto(repository.save(req))));
        }).orElse(ResponseEntity.notFound().build());
    }

    private TierConfigDto toDto(TierConfiguration t) {
        return TierConfigDto.builder()
                .id(t.getId())
                .tierName(t.getTierName())
                .objectType(t.getObjectType())
                .commissionRate(t.getCommissionRate())
                .maxActiveBookings(t.getMaxActiveBookings())
                .searchBoostFactor(t.getSearchBoostFactor())
                .responseSlaHours(t.getResponseSlaHours())
                .features(t.getFeatures())
                .build();
    }
}
