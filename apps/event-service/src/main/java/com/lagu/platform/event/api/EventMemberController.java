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

import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * One user's standing on one event, for another service to authorise against.
     *
     * <p>booking-service is the first caller: a booking carries an `eventId`, but booking-service
     * has no idea what an event membership is, so a co-host asking to see the event's inquiries
     * could not be told apart from a stranger passing the same id. Rather than teach it the
     * membership model, it asks this and enforces the answer itself — the same shape every other
     * cross-service authorization here takes.
     *
     * <p>Internal callers only. It answers a question about a third party, which is exactly what
     * a user-facing endpoint must not do: `GET /members` already exists for a member reading
     * their own event, and it requires being in that event to call.
     */
    @GetMapping("/{targetUserId}/membership")
    public ResponseEntity<ApiResponse<EventMemberResponse>> membership(@PathVariable UUID eventId,
                                                                      @PathVariable UUID targetUserId) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !ctx.isInternalService()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal callers only");
        }
        return ResponseEntity.ok(ApiResponse.ok(memberService.membershipOf(eventId, targetUserId)));
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
