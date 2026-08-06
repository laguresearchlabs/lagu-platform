package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventJoinRequest;
import com.lagu.platform.event.domain.EventJoinRequestRepository;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.dto.CreateJoinRequestRequest;
import com.lagu.platform.event.dto.EventMemberResponse;
import com.lagu.platform.event.dto.InviteMemberRequest;
import com.lagu.platform.event.dto.JoinRequestResponse;
import com.lagu.platform.event.dto.UpdateMemberRoleRequest;
import com.lagu.platform.membership.MembershipPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventMemberService {

    private static final List<String> VALID_ROLES = List.of("ADMIN", "MAINTAINER", "INVITEE");
    /** Narrower than canManage()'s {ADMIN, MAINTAINER} authorization gate — see
     *  EventMembershipPermissionEvaluator.gateRoles() for that set. A MAINTAINER doesn't count
     *  toward "is there still someone who can manage this event" for this guard. */
    private static final Set<String> LAST_MANAGER_ROLES = Set.of("ADMIN");

    private final EventRepository            eventRepo;
    private final EventMemberRepository      memberRepo;
    private final EventJoinRequestRepository joinRequestRepo;

    public List<EventMemberResponse> list(UUID eventId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireMember(event, requesterId);
        return memberRepo.findByTenantId(event.getTenantId()).stream()
                .filter(m -> !"REMOVED".equals(m.getStatus()))
                .map(this::toResponse).toList();
    }

    @Transactional
    public EventMemberResponse invite(UUID eventId, UUID requesterId, InviteMemberRequest req) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        validateRole(req.getRole());

        EventMember member = memberRepo.findByTenantIdAndUserId(event.getTenantId(), req.getUserId())
                .orElseGet(() -> {
                    EventMember m = new EventMember();
                    m.setTenantId(event.getTenantId());
                    m.setUserId(req.getUserId());
                    return m;
                });

        if (member.getId() != null && !"DECLINED".equals(member.getStatus())
                && !"REMOVED".equals(member.getStatus())) {
            throw new ValidationException("User is already a member of this event");
        }

        member.setRole(req.getRole() != null ? req.getRole().toUpperCase() : "INVITEE");
        member.setStatus("INVITED");
        member.setGuestNote(req.getGuestNote());
        member.setInvitedBy(requesterId);
        member.setRemovedBy(null);
        member.setRemovedAt(null);
        memberRepo.save(member);

        log.info("User {} invited {} to event {} as {}", requesterId, req.getUserId(), eventId, member.getRole());
        return toResponse(member);
    }

    @Transactional
    public EventMemberResponse updateRole(UUID eventId, UUID requesterId, UUID targetUserId, UpdateMemberRoleRequest req) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        validateRole(req.getRole());
        MembershipPolicy.requireNotSelf(requesterId, targetUserId);

        EventMember member = memberRepo.findByTenantIdAndUserIdAndStatusNot(event.getTenantId(), targetUserId, "REMOVED")
                .orElseThrow(() -> new ResourceNotFoundException("EventMember", targetUserId.toString()));

        String newRole = req.getRole().toUpperCase();
        List<EventMember> allMembers = memberRepo.findByTenantId(event.getTenantId());
        MembershipPolicy.requireManagerRemainsAfterMutation(allMembers, targetUserId, newRole, LAST_MANAGER_ROLES);

        member.setRole(newRole);
        member.setUpdatedBy(requesterId);
        member.setUpdatedAt(Instant.now());
        return toResponse(memberRepo.save(member));
    }

    @Transactional
    public void remove(UUID eventId, UUID requesterId, UUID targetUserId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        MembershipPolicy.requireNotSelf(requesterId, targetUserId);

        if (targetUserId.equals(event.getOwnerUserId())) {
            throw new ValidationException("Cannot remove the event owner");
        }
        EventMember member = memberRepo.findByTenantIdAndUserIdAndStatusNot(event.getTenantId(), targetUserId, "REMOVED")
                .orElseThrow(() -> new ResourceNotFoundException("EventMember", targetUserId.toString()));

        List<EventMember> allMembers = memberRepo.findByTenantId(event.getTenantId());
        MembershipPolicy.requireManagerRemainsAfterMutation(allMembers, targetUserId, null, LAST_MANAGER_ROLES);

        member.setStatus("REMOVED");
        member.setRemovedBy(requesterId);
        member.setRemovedAt(Instant.now());
        memberRepo.save(member);
    }

    @Transactional
    public EventMemberResponse setMuted(UUID eventId, UUID requesterId, boolean muted) {
        Event event = requireEvent(eventId);
        EventMember member = requireMember(event, requesterId); // a user mutes/unmutes themself
        member.setMuted(muted);
        return toResponse(memberRepo.save(member));
    }

    /** The invited user accepts or declines their own INVITED membership row. */
    @Transactional
    public EventMemberResponse respondToInvite(UUID eventId, UUID userId, boolean accept) {
        Event event = requireEvent(eventId);
        EventMember member = memberRepo.findByTenantIdAndUserId(event.getTenantId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not invited to this event"));
        if (!"INVITED".equals(member.getStatus())) {
            throw new ValidationException("No pending invitation to respond to");
        }
        member.setStatus(accept ? "ACCEPTED" : "DECLINED");
        return toResponse(memberRepo.save(member));
    }

    // ── join requests ────────────────────────────────────────────────────────

    @Transactional
    public JoinRequestResponse requestToJoin(UUID eventId, UUID userId, CreateJoinRequestRequest req) {
        Event event = requireEvent(eventId);
        memberRepo.findByTenantIdAndUserId(event.getTenantId(), userId)
                .filter(m -> !"DECLINED".equals(m.getStatus()) && !"REMOVED".equals(m.getStatus()))
                .ifPresent(m -> { throw new ValidationException("Already a member of this event"); });
        // Only a still-PENDING request blocks a new one. A settled row (REJECTED, or APPROVED
        // for a membership since declined/removed) is revived in place rather than added
        // alongside — event_join_request is UNIQUE (tenant_id, user_id), and this mirrors how
        // invite() above reuses a DECLINED/REMOVED member row. Reviving loses the previous
        // review's audit fields, which nothing reads back; created_at stays at the first ask
        // (the column is updatable = false) and isn't surfaced in the organizer's list.
        Optional<EventJoinRequest> existing = joinRequestRepo.findByTenantIdAndUserId(event.getTenantId(), userId);
        existing.filter(r -> "PENDING".equals(r.getStatus())).ifPresent(r -> {
            throw new ValidationException("A join request is already pending for this event");
        });

        EventJoinRequest jr = existing.orElseGet(EventJoinRequest::new);
        jr.setTenantId(event.getTenantId());
        jr.setUserId(userId);
        jr.setRequestedRole(req.getRequestedRole() != null ? req.getRequestedRole().toUpperCase() : "INVITEE");
        jr.setMessage(req.getMessage());
        jr.setStatus("PENDING");
        jr.setReviewedByUserId(null);
        jr.setReviewedAt(null);
        joinRequestRepo.save(jr);
        return toResponse(jr);
    }

    public List<JoinRequestResponse> listPending(UUID eventId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        return joinRequestRepo.findByTenantIdAndStatus(event.getTenantId(), "PENDING").stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public EventMemberResponse approve(UUID eventId, UUID requesterId, UUID joinRequestId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);

        EventJoinRequest jr = joinRequestRepo.findById(joinRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("JoinRequest", joinRequestId.toString()));
        if (!jr.getTenantId().equals(event.getTenantId())) {
            throw new ResourceNotFoundException("JoinRequest", joinRequestId.toString());
        }

        Optional<EventMember> existing = memberRepo.findByTenantIdAndUserId(event.getTenantId(), jr.getUserId());
        if (existing.isPresent() && !"DECLINED".equals(existing.get().getStatus())
                && !"REMOVED".equals(existing.get().getStatus())) {
            jr.setStatus("REJECTED");
            jr.setReviewedByUserId(requesterId);
            jr.setReviewedAt(Instant.now());
            joinRequestRepo.save(jr);
            throw new ValidationException(
                    "User is already a member of this event; join request rejected due to conflict");
        }

        jr.setStatus("APPROVED");
        jr.setReviewedByUserId(requesterId);
        jr.setReviewedAt(Instant.now());
        joinRequestRepo.save(jr);

        EventMember member = existing.orElseGet(() -> {
            EventMember m = new EventMember();
            m.setTenantId(event.getTenantId());
            m.setUserId(jr.getUserId());
            return m;
        });
        member.setRole(jr.getRequestedRole());
        member.setStatus("ACCEPTED");
        member.setInvitedBy(requesterId);
        member.setRemovedBy(null);
        member.setRemovedAt(null);
        return toResponse(memberRepo.save(member));
    }

    @Transactional
    public void reject(UUID eventId, UUID requesterId, UUID joinRequestId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);

        EventJoinRequest jr = joinRequestRepo.findById(joinRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("JoinRequest", joinRequestId.toString()));
        if (!jr.getTenantId().equals(event.getTenantId())) {
            throw new ResourceNotFoundException("JoinRequest", joinRequestId.toString());
        }
        jr.setStatus("REJECTED");
        jr.setReviewedByUserId(requesterId);
        jr.setReviewedAt(Instant.now());
        joinRequestRepo.save(jr);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void validateRole(String role) {
        if (role != null && !VALID_ROLES.contains(role.toUpperCase())) {
            throw new ValidationException("Invalid role: " + role);
        }
    }

    private Event requireEvent(UUID eventId) {
        return eventRepo.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId.toString()));
    }

    private EventMember requireMember(Event event, UUID userId) {
        return memberRepo.findByTenantIdAndUserIdAndStatusNot(event.getTenantId(), userId, "REMOVED")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this event"));
    }

    private EventMember requireManager(Event event, UUID userId) {
        EventMember member = requireMember(event, userId);
        if (!member.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN or MAINTAINER role required");
        }
        return member;
    }

    private EventMemberResponse toResponse(EventMember m) {
        return EventMemberResponse.builder()
                .id(m.getId()).userId(m.getUserId()).role(m.getRole()).status(m.getStatus())
                .guestNote(m.getGuestNote()).muted(m.isMuted()).invitedBy(m.getInvitedBy())
                .joinedAt(m.getJoinedAt() != null ? m.getJoinedAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .build();
    }

    private JoinRequestResponse toResponse(EventJoinRequest jr) {
        return JoinRequestResponse.builder()
                .id(jr.getId()).userId(jr.getUserId()).requestedRole(jr.getRequestedRole())
                .status(jr.getStatus()).message(jr.getMessage())
                .reviewedByUserId(jr.getReviewedByUserId())
                .reviewedAt(jr.getReviewedAt() != null ? jr.getReviewedAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .createdAt(jr.getCreatedAt() != null ? jr.getCreatedAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .build();
    }
}
