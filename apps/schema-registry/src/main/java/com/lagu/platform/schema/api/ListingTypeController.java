package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.dto.*;
import com.lagu.platform.schema.service.ListingTypeService;
import com.lagu.platform.schema.service.SchemaVersionService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
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

    // Reads stay open (no @RequirePermission): these endpoints are events-ui's dynamic-form
    // schema source and are fetched without a login token — see schemaService.ts.
    @GetMapping
    public ResponseEntity<ApiResponse<List<ListingTypeResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.list()));
    }

    @GetMapping("/{name}")
    public ResponseEntity<ApiResponse<ListingTypeResponse>> getByName(@PathVariable String name) {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.getByName(name)));
    }

    /**
     * The resolved field schema. Without {@code version} this is the live schema; with it, the
     * immutable snapshot published as that version — which is what lets an existing record be
     * edited against the schema it was actually authored under (ADR-11) rather than whatever has
     * been published since.
     */
    @GetMapping("/{name}/schema")
    public ResponseEntity<ApiResponse<ListingTypeSchemaDto>> getSchema(
            @PathVariable String name,
            @RequestParam(required = false) Integer version) {
        ListingTypeSchemaDto schema = version == null
                ? listingTypeService.getSchema(name)
                : schemaVersionService.getSchemaAtVersion(name, version);
        return ResponseEntity.ok(ApiResponse.ok(schema));
    }

    /** Publish metadata for a version (classification, summary, who published it) — not the
     *  schema itself; use {@code GET /{name}/schema?version=N} for that. */
    @GetMapping("/{name}/schema/version/{version}")
    public ResponseEntity<ApiResponse<SchemaVersionResponse>> getSchemaVersion(
            @PathVariable String name,
            @PathVariable int version) {
        return ResponseEntity.ok(ApiResponse.ok(schemaVersionService.getVersion(name, version)));
    }

    @PostMapping
    @RequirePermission(resource = "OBJECT_TYPE", action = "CREATE")
    public ResponseEntity<ApiResponse<ListingTypeResponse>> create(
            @Valid @RequestBody ListingTypeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(listingTypeService.create(req)));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = "OBJECT_TYPE", action = "UPDATE")
    public ResponseEntity<ApiResponse<ListingTypeResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody ListingTypeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.update(id, req)));
    }

    @PostMapping("/{name}/sections")
    @RequirePermission(resource = "OBJECT_TYPE", action = "UPDATE")
    public ResponseEntity<ApiResponse<ListingTypeResponse>> addSection(
            @PathVariable String name,
            @Valid @RequestBody ListingTypeRequest.SectionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(listingTypeService.addSection(name, req)));
    }

    @PostMapping("/{name}/publish")
    @RequirePermission(resource = "OBJECT_TYPE", action = "UPDATE")
    public ResponseEntity<ApiResponse<SchemaVersionResponse>> publish(
            @PathVariable String name,
            @RequestBody PublishSchemaRequest req) {
        // publishedBy comes from the trusted gateway-injected context, not a raw client header —
        // a caller could otherwise claim to be anyone for this audit field.
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        String publishedBy = (ctx != null && ctx.getUserId() != null) ? ctx.getUserId().toString() : "system";
        return ResponseEntity.ok(ApiResponse.ok(schemaVersionService.publish(name, req, publishedBy)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = "OBJECT_TYPE", action = "DELETE")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        listingTypeService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
