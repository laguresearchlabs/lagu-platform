package com.lagu.platform.record.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.record.dto.*;
import com.lagu.platform.record.service.RecordService;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService service;

    @GetMapping
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<PageResult<RecordResponse>>> list(
            @RequestParam String objectType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(objectType, status, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<RecordResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    @RequirePermission(resource = "RECORD", action = "CREATE")
    public ResponseEntity<ApiResponse<RecordResponse>> create(@Valid @RequestBody CreateRecordRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(req)));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    public ResponseEntity<ApiResponse<RecordResponse>> update(@PathVariable UUID id,
                                                               @Valid @RequestBody UpdateRecordRequest req) {
        return respond(service.update(id, req));
    }

    @PatchMapping("/{id}")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    public ResponseEntity<ApiResponse<RecordResponse>> patch(@PathVariable UUID id,
                                                              @RequestBody Map<String, Object> partialData) {
        return respond(service.patch(id, partialData));
    }

    /**
     * 202 when the edit was held for review rather than applied, 200 when it landed.
     *
     * The status code carries the distinction because the body cannot: a held response returns the
     * record's *current* values, which are indistinguishable from a successful no-op edit. A client
     * that treats 2xx as "saved" is now wrong in a visible way rather than a silent one.
     */
    private static ResponseEntity<ApiResponse<RecordResponse>> respond(RecordResponse result) {
        return result.getPendingChangeSetId() != null
                ? ResponseEntity.accepted().body(ApiResponse.ok(result))
                : ResponseEntity.ok(ApiResponse.ok(result));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = "RECORD", action = "DELETE")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/status")
    @RequirePermission(resource = "RECORD", action = "TRANSITION")
    public ResponseEntity<ApiResponse<RecordResponse>> requestTransition(
            @PathVariable UUID id,
            @Valid @RequestBody StatusTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.requestTransition(id, req)));
    }

    @GetMapping("/{id}/history")
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<PageResult<RecordResponse>>> getHistory(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.getHistory(id, page, size)));
    }
}
