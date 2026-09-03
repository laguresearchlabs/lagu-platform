package com.lagu.platform.event.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.event.dto.CreateInvitationRequest;
import com.lagu.platform.event.dto.EventInvitationResponse;
import com.lagu.platform.event.service.EventInvitationService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Invitations addressed to a contact rather than a user id.
 *
 * <p>Separate from {@link EventMemberController} because they are a different thing until they
 * are claimed: a member row is somebody who is on the event, and an invitation is somebody who
 * has been asked and may not even have an account. Folding them into one endpoint would mean a
 * member list whose rows sometimes have no user.
 */
@RestController
@RequiredArgsConstructor
public class EventInvitationController {

    private final EventInvitationService invitationService;

    /** Managers only — see the service. Idempotent for an address already invited. */
    @PostMapping("/api/v1/events/{eventId}/invitations")
    public ResponseEntity<ApiResponse<EventInvitationResponse>> invite(
            @PathVariable UUID eventId,
            @Valid @RequestBody CreateInvitationRequest req) {
        EventInvitationResponse created = invitationService.invite(
                eventId, EventController.requireUserId(),
                req.getEmail(), req.getPhone(), req.getCountryCode(), req.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * Outstanding invitations for one event.
     *
     * <p>Managers only, unlike the member list: these rows are contact details for people who
     * are not on the event and never agreed to be listed on it.
     */
    @GetMapping("/api/v1/events/{eventId}/invitations")
    public ResponseEntity<ApiResponse<List<EventInvitationResponse>>> list(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(
                invitationService.listPending(eventId, EventController.requireUserId())));
    }

    @DeleteMapping("/api/v1/events/{eventId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID eventId, @PathVariable UUID invitationId) {
        invitationService.revoke(eventId, EventController.requireUserId(), invitationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Claims everything waiting for the caller's own address or verified phone.
     *
     * <p>No body, deliberately. The contact details come from the security context — what the
     * gateway resolved for this session from the caller's JWT — never from the request, because
     * "name a contact and join whatever it was invited to" is an account takeover with extra
     * steps. The phone is present only when the JWT's {@code phoneVerified} claim was true, so an
     * unverified number can never be used to claim someone else's invitation.
     *
     * <p>Not scoped to an event: somebody invited to three parties before they had an account
     * gets all three on the visit they finally sign up. The client calls this once after sign-in.
     */
    @PostMapping("/api/v1/events/invitations/claim")
    public ResponseEntity<ApiResponse<Map<String, Object>>> claim() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID userId = EventController.requireUserId();
        String email = ctx == null ? null : ctx.getUserEmail();
        String phone = ctx == null ? null : ctx.getUserPhone();

        int claimed = invitationService.claimFor(userId, email, phone);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("claimed", claimed)));
    }
}
