package com.lagu.platform.event.service;

import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.client.RecordServiceClient;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.dto.CreateEventRequest;
import com.lagu.platform.event.dto.EventResponse;
import com.lagu.platform.event.dto.EventSummaryResponse;
import com.lagu.platform.event.dto.LinkVendorRequest;
import com.lagu.platform.event.dto.TransitionRequest;
import com.lagu.platform.event.dto.UpdateEventRequest;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository       eventRepo;
    private final EventMemberRepository memberRepo;
    private final RecordServiceClient   recordClient;

    @Transactional
    public EventResponse create(CreateEventRequest req, UUID userId) {
        // Generated up front (not left to JPA at save time) since the same value doubles as the
        // org-partition key record-service needs before the Record even exists — see Event.id's
        // doc comment.
        UUID id = UUID.randomUUID();

        // Create the canonical record in record-service — validated against the schema-registry
        // definition for this objectType (BIRTHDAY_EVENT, WEDDING_EVENT, ...).
        Map<String, Object> recordResponse = recordClient.createRecord(
                id, userId, req.getObjectType().toUpperCase(), req.getData());
        UUID recordId = recordClient.extractRecordId(recordResponse);
        if (recordId == null) {
            throw new ValidationException("Failed to create " + req.getObjectType() + " record");
        }

        Event event = new Event();
        event.setId(id);
        event.setRecordId(recordId);
        event.setObjectType(req.getObjectType().toUpperCase());
        event.setOwnerUserId(userId);
        eventRepo.save(event);

        EventMember owner = new EventMember();
        owner.setTenantId(id);
        owner.setUserId(userId);
        owner.setRole("ADMIN");
        owner.setStatus("ACCEPTED");
        memberRepo.save(owner);

        log.info("Created event {} (objectType={}) for user={}", event.getId(), event.getObjectType(), userId);
        return toResponse(event, owner, fetchData(event));
    }

    /**
     * Read access is intentionally broader than requireMember (ACCEPTED-only): an INVITED
     * member must be able to view the event well enough to decide whether to accept via
     * POST /{id}/members/me/accept — there is no other endpoint that would let them discover
     * what they're being invited to first. DECLINED is allowed too so a past decision remains
     * visible; only non-members are rejected.
     */
    public EventResponse get(UUID eventId, UUID userId) {
        Event event = requireEvent(eventId);
        Map<String, Object> data = fetchData(event);

        Optional<EventMember> member = memberRepo.findByTenantIdAndUserId(event.getTenantId(), userId);
        if (member.isEmpty()) {
            // Non-members may view a PUBLIC event read-only, well enough to send a join
            // request (mirrors event-nest's old share-link preview). A PLATFORM_ADMIN may view
            // any event regardless of visibility, mirroring record-service's findForContext —
            // everyone else who isn't a member and the event isn't PUBLIC gets 403.
            PlatformSecurityContext ctx = GatewayHeaderFilter.current();
            boolean isPlatformAdmin = ctx != null && ctx.isPlatformAdmin();
            if (!isPlatformAdmin && !"PUBLIC".equals(data.get("visibility"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this event");
            }
            return toResponse(event, null, data);
        }
        return toResponse(event, member.get(), data);
    }

    /** Platform-admin listing across every event, regardless of membership. Caller must be
     *  authorized by EventController.requirePlatformAdmin() before this is invoked. */
    public PageResult<EventSummaryResponse> listForAdmin(String objectType, String status, int page, int size) {
        String ot = (objectType != null && !objectType.isBlank()) ? objectType.toUpperCase() : null;
        String st = (status != null && !status.isBlank()) ? status.toUpperCase() : null;
        PageRequest pageReq = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return PageResult.from(eventRepo.search(ot, st, pageReq).map(EventSummaryResponse::from));
    }

    /** Events the caller is a member of, accepted or still-pending — pending ones are how an
     *  invited user discovers they have an invitation to respond to (see get()'s note above). */
    public List<EventResponse> listMine(UUID userId) {
        return memberRepo.findByUserId(userId).stream()
                .filter(m -> !"DECLINED".equals(m.getStatus()))
                .map(m -> eventRepo.findById(m.getTenantId()).map(e -> toResponse(e, m, null)))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    @Transactional
    public EventResponse update(UUID eventId, UUID userId, UpdateEventRequest req) {
        Event event = requireEvent(eventId);
        EventMember member = requireManager(event, userId);

        Map<String, Object> recordResponse = recordClient.updateRecord(
                event.getRecordId(), event.getTenantId(), userId, req.getData());
        if (recordResponse == null) {
            throw new ValidationException("Failed to update event data");
        }
        return toResponse(event, member, extractData(recordResponse));
    }

    @Transactional
    public void requestTransition(UUID eventId, UUID userId, TransitionRequest req) {
        Event event = requireEvent(eventId);
        requireManager(event, userId);
        // Fire-and-forget: record-service stages this via its outbox and workflow-service
        // processes it asynchronously. Current authoritative state is available via
        // GET /api/v1/records/{recordId}/workflow (record-service, gatewayed directly to callers).
        recordClient.requestTransition(event.getRecordId(), event.getTenantId(), userId, req.getTrigger());
    }

    @Transactional
    public void linkVendor(UUID eventId, UUID userId, LinkVendorRequest req) {
        Event event = requireEvent(eventId);
        requireManager(event, userId);
        recordClient.createRelationship(event.getRecordId(), event.getTenantId(), userId,
                req.getRelationshipName(), req.getTargetRecordId());
    }

    @Transactional
    public void unlinkVendor(UUID eventId, UUID userId, String relationshipName, UUID targetRecordId) {
        Event event = requireEvent(eventId);
        requireManager(event, userId);
        recordClient.deleteRelationship(event.getRecordId(), event.getTenantId(), relationshipName, targetRecordId);
    }

    public List<Map<String, Object>> listVendorLinks(UUID eventId, UUID userId) {
        Event event = requireEvent(eventId);
        requireMember(event, userId);
        return recordClient.listRelationships(event.getRecordId(), event.getTenantId());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    Event requireEvent(UUID eventId) {
        return eventRepo.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId.toString()));
    }

    /** Any accepted member (ADMIN/MAINTAINER/INVITEE) may read. */
    EventMember requireMember(Event event, UUID userId) {
        EventMember member = memberRepo.findByTenantIdAndUserId(event.getTenantId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this event"));
        if (!"ACCEPTED".equals(member.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Membership not accepted");
        }
        return member;
    }

    /** Only ADMIN/MAINTAINER may mutate. */
    EventMember requireManager(Event event, UUID userId) {
        EventMember member = requireMember(event, userId);
        if (!member.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN or MAINTAINER role required");
        }
        return member;
    }

    private Map<String, Object> fetchData(Event event) {
        Map<String, Object> record = recordClient.getRecord(event.getRecordId(), event.getTenantId());
        return extractData(record);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> recordResponse) {
        if (recordResponse == null) return Map.of();
        Object data = recordResponse.get("data");
        if (data instanceof Map<?, ?> outer) {
            Object inner = outer.get("data");
            if (inner instanceof Map<?, ?> m) return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private EventResponse toResponse(Event event, EventMember viewer, Map<String, Object> data) {
        return EventResponse.builder()
                .id(event.getId())
                .recordId(event.getRecordId())
                .objectType(event.getObjectType())
                .ownerUserId(event.getOwnerUserId())
                .status(event.getStatus())
                .myRole(viewer != null ? viewer.getRole() : null)
                .myStatus(viewer != null ? viewer.getStatus() : null)
                .data(data)
                .createdAt(event.getCreatedAt().atOffset(java.time.ZoneOffset.UTC))
                .updatedAt(event.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC))
                .build();
    }
}
