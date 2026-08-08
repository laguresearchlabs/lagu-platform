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
import com.lagu.platform.event.dto.SharePreviewResponse;
import com.lagu.platform.event.dto.TransitionRequest;
import com.lagu.platform.event.dto.UpdateEventRequest;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository       eventRepo;
    private final EventMemberRepository memberRepo;
    private final RecordServiceClient   recordClient;

    /**
     * Fans out listMine()'s per-event record fetches. Blocking IO, so it is deliberately not the
     * common ForkJoinPool; virtual threads keep the width unbounded-but-cheap, which suits a
     * workload that is entirely waiting on record-service.
     */
    private final ExecutorService listHydrationExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    @jakarta.annotation.PreDestroy
    void shutdownExecutor() {
        listHydrationExecutor.shutdown();
    }

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

    /**
     * Link-preview projection for GET /share/{id} — the one event endpoint reachable without
     * an identity, because the crawlers that render Open Graph cards (WhatsApp, Twitterbot,
     * Facebook) can't authenticate. Anything not explicitly PUBLIC 404s rather than 403s, so
     * an unauthenticated caller can't use this to confirm a private event even exists; and
     * PUBLIC events expose only the handful of fields the share page already shows every
     * logged-in visitor (see SharePreviewResponse).
     */
    public SharePreviewResponse getSharePreview(UUID eventId) {
        Event event = requireEvent(eventId);
        Map<String, Object> data = fetchData(event);
        if (!"PUBLIC".equals(data.get("visibility"))) {
            throw new ResourceNotFoundException("Event", eventId.toString());
        }
        return SharePreviewResponse.builder()
                .objectType(event.getObjectType())
                .title(str(data.get("name")))
                .description(str(data.get("description")))
                .coverImage(str(data.get("cover_image")))
                .startDatetime(str(data.get("start_datetime")))
                .city(str(data.get("city")))
                .state(str(data.get("state")))
                .build();
    }

    /** Schema-driven values arrive as loosely-typed JSON — anything non-textual is dropped
     *  rather than coerced, since only strings are of use to a preview card. */
    private String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** Platform-admin listing across every event, regardless of membership. Caller must be
     *  authorized by EventController.requirePlatformAdmin() before this is invoked. */
    public PageResult<EventSummaryResponse> listForAdmin(String objectType, String status, int page, int size) {
        String ot = (objectType != null && !objectType.isBlank()) ? objectType.toUpperCase() : null;
        String st = (status != null && !status.isBlank()) ? status.toUpperCase() : null;
        PageRequest pageReq = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Event> events = eventRepo.search(ot, st, pageReq);

        // Every row's name is fetched from record-service, so they are all started before any is
        // waited on — mapping them one at a time would cost the page `size` sequential round
        // trips. Same executor and reasoning as listMine().
        Map<UUID, CompletableFuture<String>> names = events.getContent().stream()
                .collect(Collectors.toMap(Event::getId, e -> CompletableFuture.supplyAsync(
                        () -> fetchData(e).get("name") instanceof String s ? s : null,
                        listHydrationExecutor)));

        return PageResult.from(events.map(event -> {
            EventSummaryResponse summary = EventSummaryResponse.from(event);
            summary.setName(names.get(event.getId()).join());
            return summary;
        }));
    }

    /**
     * Events the caller is a member of, accepted or still-pending — pending ones are how an
     * invited user discovers they have an invitation to respond to (see get()'s note above).
     *
     * <p>Each row carries its full record `data`, same as the single-event GET. It used not to,
     * and the client compensated by re-fetching every event individually — one HTTP round trip
     * per event on top of this one, each of which came straight back here and did the
     * record-service call below anyway. Doing it server-side collapses that to a single
     * request; the per-event record fetches still happen, but in parallel and without the
     * browser in the loop.
     */
    public List<EventResponse> listMine(UUID userId) {
        List<EventMember> memberships = memberRepo.findByUserId(userId).stream()
                .filter(m -> !"DECLINED".equals(m.getStatus()))
                .toList();
        if (memberships.isEmpty()) {
            return List.of();
        }

        Map<UUID, Event> events = eventRepo.findAllById(memberships.stream().map(EventMember::getTenantId).toList())
                .stream().collect(Collectors.toMap(Event::getId, e -> e));

        // Concurrent because each fetchData is an independent blocking call to record-service;
        // serially this costs (event count x round trip) before the first byte goes out. Runs on
        // a dedicated pool rather than the common ForkJoinPool, which is sized for CPU work and
        // would be starved by blocking IO.
        List<CompletableFuture<EventResponse>> futures = memberships.stream()
                .map(m -> events.get(m.getTenantId()) == null ? null : CompletableFuture.supplyAsync(
                        () -> toResponse(events.get(m.getTenantId()), m, fetchData(events.get(m.getTenantId()))),
                        listHydrationExecutor))
                .filter(Objects::nonNull)
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparing(EventResponse::getCreatedAt).reversed())
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
