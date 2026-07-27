package com.lagu.platform.event.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.event.dto.EventMemberResponse;
import com.lagu.platform.event.dto.InviteMemberRequest;
import com.lagu.platform.event.dto.UpdateMemberRoleRequest;
import com.lagu.platform.event.service.EventMemberService;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/members")
@RequiredArgsConstructor
public class EventMemberController {

    private final EventMemberService memberService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EventMemberResponse>>> list(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.list(eventId, EventController.requireUserId())));
    }

    @RequirePermission(resource = "EVENT_MEMBER", action = "CREATE")
    @PostMapping
    public ResponseEntity<ApiResponse<EventMemberResponse>> invite(@PathVariable UUID eventId,
                                                                     @Valid @RequestBody InviteMemberRequest req) {
        EventMemberResponse member = memberService.invite(eventId, EventController.requireUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(member));
    }

    @RequirePermission(resource = "EVENT_MEMBER", action = "UPDATE")
    @PatchMapping("/{targetUserId}/role")
    public ResponseEntity<ApiResponse<EventMemberResponse>> updateRole(@PathVariable UUID eventId,
                                                                        @PathVariable UUID targetUserId,
                                                                        @Valid @RequestBody UpdateMemberRoleRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberService.updateRole(eventId, EventController.requireUserId(), targetUserId, req)));
    }

    @RequirePermission(resource = "EVENT_MEMBER", action = "DELETE")
    @DeleteMapping("/{targetUserId}")
    public ResponseEntity<Void> remove(@PathVariable UUID eventId, @PathVariable UUID targetUserId) {
        memberService.remove(eventId, EventController.requireUserId(), targetUserId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/me/mute")
    public ResponseEntity<ApiResponse<EventMemberResponse>> mute(@PathVariable UUID eventId,
                                                                   @RequestParam(defaultValue = "true") boolean muted) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberService.setMuted(eventId, EventController.requireUserId(), muted)));
    }

    @PatchMapping("/me/accept")
    public ResponseEntity<ApiResponse<EventMemberResponse>> acceptInvite(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberService.respondToInvite(eventId, EventController.requireUserId(), true)));
    }

    @PatchMapping("/me/decline")
    public ResponseEntity<ApiResponse<EventMemberResponse>> declineInvite(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberService.respondToInvite(eventId, EventController.requireUserId(), false)));
    }
}
