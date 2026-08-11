package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may act on an event.
 *
 * <p>Shared rather than repeated because it now governs more than one service — posts and the
 * photo album — and the failure mode of a second copy is silent: a new surface that forgets the
 * accepted-membership check exposes an event's contents to anyone who knows its id.
 */
@Component
@RequiredArgsConstructor
public class EventMembershipGuard {

    private final EventRepository eventRepo;
    private final EventMemberRepository memberRepo;

    public Event requireEvent(java.util.UUID eventId) {
        return eventRepo.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId.toString()));
    }

    /**
     * An accepted member. An invitation that has not been accepted is deliberately not enough —
     * being invited to an event is not the same as being in it.
     */
    public EventMember requireMember(Event event, java.util.UUID userId) {
        EventMember member = memberRepo.findByTenantIdAndUserId(event.getTenantId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this event"));
        if (!"ACCEPTED".equals(member.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Membership not accepted");
        }
        return member;
    }

    public EventMember requireManager(Event event, java.util.UUID userId) {
        EventMember member = requireMember(event, userId);
        if (!member.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN or MAINTAINER role required");
        }
        return member;
    }
}
