package com.lagu.platform.event.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.event.dto.CreateJoinRequestRequest;
import com.lagu.platform.event.dto.EventMemberResponse;
import com.lagu.platform.event.dto.JoinRequestResponse;
import com.lagu.platform.event.service.EventMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/join-requests")
@RequiredArgsConstructor
public class EventJoinRequestController {

    private final EventMemberService memberService;

    @PostMapping
    public ResponseEntity<ApiResponse<JoinRequestResponse>> requestToJoin(
            @PathVariable UUID eventId, @Valid @RequestBody CreateJoinRequestRequest req) {
        JoinRequestResponse jr = memberService.requestToJoin(eventId, EventController.requireUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(jr));
    }

    /** ADMIN/MAINTAINER only — see EventMemberService.listPending. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<JoinRequestResponse>>> listPending(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberService.listPending(eventId, EventController.requireUserId())));
    }

    @PatchMapping("/{joinRequestId}/approve")
    public ResponseEntity<ApiResponse<EventMemberResponse>> approve(@PathVariable UUID eventId,
                                                                      @PathVariable UUID joinRequestId) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberService.approve(eventId, EventController.requireUserId(), joinRequestId)));
    }

    @DeleteMapping("/{joinRequestId}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID eventId, @PathVariable UUID joinRequestId) {
        memberService.reject(eventId, EventController.requireUserId(), joinRequestId);
        return ResponseEntity.noContent().build();
    }
}
