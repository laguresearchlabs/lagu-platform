package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.domain.CountryValidationConfig;
import com.lagu.platform.metadata.domain.CountryValidationConfigRepository;
import com.lagu.platform.metadata.dto.CountryValidationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/country-configs")
@RequiredArgsConstructor
public class CountryValidationController {

    private final CountryValidationConfigRepository repository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CountryValidationDto>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(
                repository.findAll().stream().map(this::toDto).toList()));
    }

    @GetMapping("/{country}")
    public ResponseEntity<ApiResponse<CountryValidationDto>> getByCountry(
            @PathVariable String country) {
        return repository.findByCountryAndActiveTrue(country.toUpperCase())
                .map(c -> ResponseEntity.ok(ApiResponse.ok(toDto(c))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CountryValidationDto>> create(
            @RequestBody CountryValidationConfig req) {
        req.setCountry(req.getCountry().toUpperCase());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(toDto(repository.save(req))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CountryValidationDto>> update(
            @PathVariable UUID id, @RequestBody CountryValidationConfig req) {
        return repository.findById(id).map(existing -> {
            req.setId(id);
            req.setCountry(req.getCountry().toUpperCase());
            req.setCreatedAt(existing.getCreatedAt());
            return ResponseEntity.ok(ApiResponse.ok(toDto(repository.save(req))));
        }).orElse(ResponseEntity.notFound().build());
    }

    private CountryValidationDto toDto(CountryValidationConfig c) {
        return CountryValidationDto.builder()
                .id(c.getId())
                .country(c.getCountry())
                .rules(c.getRules())
                .currency(c.getCurrency())
                .taxLabel(c.getTaxLabel())
                .dialCode(c.getDialCode())
                .build();
    }
}
