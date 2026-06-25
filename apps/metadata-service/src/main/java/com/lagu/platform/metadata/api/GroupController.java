package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.dto.*;
import com.lagu.platform.metadata.service.GroupService;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService service;

    @GetMapping
    @RequirePermission(resource = "GROUP", action = "READ")
    public ResponseEntity<ApiResponse<List<GroupResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listGroups()));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "GROUP", action = "READ")
    public ResponseEntity<ApiResponse<GroupResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getGroup(id)));
    }

    @PostMapping
    @RequirePermission(resource = "GROUP", action = "CREATE")
    public ResponseEntity<ApiResponse<GroupResponse>> create(@Valid @RequestBody GroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.createGroup(req)));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = "GROUP", action = "UPDATE")
    public ResponseEntity<ApiResponse<GroupResponse>> update(@PathVariable UUID id,
                                                              @Valid @RequestBody GroupRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateGroup(id, req)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = "GROUP", action = "DELETE")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivateGroup(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members")
    @RequirePermission(resource = "GROUP", action = "UPDATE")
    public ResponseEntity<ApiResponse<MemberResponse>> addMember(@PathVariable UUID id,
                                                                   @Valid @RequestBody AddMemberRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.addMember(id, req)));
    }

    @PutMapping("/{id}/members/{userId}")
    @RequirePermission(resource = "GROUP", action = "UPDATE")
    public ResponseEntity<ApiResponse<MemberResponse>> updateMember(@PathVariable UUID id,
                                                                      @PathVariable UUID userId,
                                                                      @Valid @RequestBody AddMemberRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateMemberRole(id, userId, req)));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @RequirePermission(resource = "GROUP", action = "UPDATE")
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        service.removeMember(id, userId);
        return ResponseEntity.noContent().build();
    }
}
