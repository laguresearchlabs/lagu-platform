package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.dto.*;
import com.lagu.platform.schema.service.ListingTypeService;
import com.lagu.platform.schema.service.SchemaVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/listing-types")
@RequiredArgsConstructor
public class ListingTypeController {

    private final ListingTypeService listingTypeService;
    private final SchemaVersionService schemaVersionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ListingTypeResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.list()));
    }

    @GetMapping("/{name}")
    public ResponseEntity<ApiResponse<ListingTypeResponse>> getByName(@PathVariable String name) {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.getByName(name)));
    }

    @GetMapping("/{name}/schema")
    public ResponseEntity<ApiResponse<ListingTypeSchemaDto>> getSchema(@PathVariable String name) {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.getSchema(name)));
    }

    @GetMapping("/{name}/schema/version/{version}")
    public ResponseEntity<ApiResponse<SchemaVersionResponse>> getSchemaVersion(
            @PathVariable String name,
            @PathVariable int version) {
        return ResponseEntity.ok(ApiResponse.ok(schemaVersionService.getVersion(name, version)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ListingTypeResponse>> create(
            @Valid @RequestBody ListingTypeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(listingTypeService.create(req)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ListingTypeResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody ListingTypeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.update(id, req)));
    }

    @PostMapping("/{name}/sections")
    public ResponseEntity<ApiResponse<ListingTypeResponse>> addSection(
            @PathVariable String name,
            @Valid @RequestBody ListingTypeRequest.SectionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.addSection(name, req)));
    }

    @PostMapping("/{name}/publish")
    public ResponseEntity<ApiResponse<SchemaVersionResponse>> publish(
            @PathVariable String name,
            @RequestBody PublishSchemaRequest req,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String publishedBy) {
        return ResponseEntity.ok(ApiResponse.ok(schemaVersionService.publish(name, req, publishedBy)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        listingTypeService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
