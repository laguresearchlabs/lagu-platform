package com.lagu.platform.record.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.record.dto.SetVerificationRequest;
import com.lagu.platform.record.dto.VerificationResponse;
import com.lagu.platform.record.service.RecordVerificationService;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/records/{recordId}/verification")
@RequiredArgsConstructor
public class RecordVerificationController {

    private final RecordVerificationService service;

    @GetMapping
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<VerificationResponse>> get(@PathVariable UUID recordId) {
        return ResponseEntity.ok(ApiResponse.ok(service.getByRecordId(recordId)));
    }

    @PutMapping
    @RequirePermission(resource = "RECORD_VERIFICATION", action = "MANAGE")
    public ResponseEntity<ApiResponse<VerificationResponse>> set(
            @PathVariable UUID recordId,
            @Valid @RequestBody SetVerificationRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.set(recordId, req)));
    }

    @PostMapping("/revoke")
    @RequirePermission(resource = "RECORD_VERIFICATION", action = "MANAGE")
    public ResponseEntity<ApiResponse<VerificationResponse>> revoke(
            @PathVariable UUID recordId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.revoke(recordId, body.get("reason"))));
    }

    @PostMapping("/expire-overdue")
    @RequirePermission(resource = "RECORD_VERIFICATION", action = "MANAGE")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> expireOverdue() {
        int count = service.expireOverdue();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("expired", count)));
    }
}
