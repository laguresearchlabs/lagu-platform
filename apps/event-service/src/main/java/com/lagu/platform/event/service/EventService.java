package com.lagu.platform.event.service;

import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.client.RecordServiceClient;
import com.lagu.platform.event.client.SchemaRegistryClient;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.domain.EventShareLink;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final SchemaRegistryClient  schemaClient;
    private final EventShareLinkService shareLinkService;

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
            // Membership, or nothing. This used to admit any authenticated caller holding the id
            // when the record said visibility == "PUBLIC", which is what made share links
            // unrevocable: the link was never the gate, so closing it changed nothing. A
            // non-member now reaches an event by redeeming a live share link (which makes them a
            // member) — see EventShareLinkService.claim() — or through the anonymous preview.
            // A PLATFORM_ADMIN still reads anything, mirroring record-service's findForContext.
            PlatformSecurityContext ctx = GatewayHeaderFilter.current();
            boolean isPlatformAdmin = ctx != null && ctx.isPlatformAdmin();
            if (!isPlatformAdmin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this event");
            }
            // Unfiltered, and the only read here that is: a platform admin reads any event whole,
            // which is what the Admin Portal's event page renders. There is no membership to
            // resolve a rung from, and inventing one would be a guess.
            return toResponse(event, null, data);
        }

        EventMember viewer = member.get();
        // Loudly rather than quietly: a member handed a stripped record would see an event with
        // no name and no date and nothing saying why. A refusal they can retry is the honest
        // answer to "we cannot currently tell what you are allowed to read".
        Map<String, Object> visible = readableData(event, viewer, data)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Cannot determine what this event may show you right now"));

        return toResponse(event, viewer, visible);
    }

    /**
     * Link-preview projection for GET /share/{token} — the one event endpoint reachable without
     * an identity, because the crawlers that render Open Graph cards (WhatsApp, Twitterbot,
     * Facebook) can't authenticate.
     *
     * <p>The token is the authorization, so there is no visibility test here: whoever minted the
     * link decided this much could be shown. What replaces it is EventShareLinkService.resolve(),
     * which 404s a token that is unknown, revoked, expired or used up — all four identically, so
     * a dead link cannot be used to confirm the event exists.
     *
     * <p>The scalars stay exactly as narrow as they were. What is new is {@code data}: the
     * record's values filtered to the fields of sections the listing type marked
     * {@code audience: PUBLIC}, so a share link can render the event as the invitation a member
     * sees rather than as a title and a blurb.
     *
     * <p>The filter is an allow-list built from the schema and nothing else — never the record's
     * own keys, and never a deny-list — so a field a type adds later is private until an admin
     * puts it in a PUBLIC section. {@link SchemaRegistryClient#publicFields} fails closed, so a
     * schema-registry that is down or slow costs this endpoint its {@code data} rather than
     * publishing a host's budget to everyone holding a forwarded link.
     */
    public SharePreviewResponse getSharePreview(String token) {
        EventShareLink link = shareLinkService.resolve(token);
        Event event = requireEvent(link.getEventId());
        Map<String, Object> data = fetchData(event);

        SchemaRegistryClient.VisibleFields publicFields = schemaClient.publicFields(event.getObjectType());

        return SharePreviewResponse.builder()
                .objectType(event.getObjectType())
                .title(str(data.get("name")))
                .description(str(data.get("description")))
                .coverImage(str(data.get("cover_image")))
                .startDatetime(str(data.get("start_datetime")))
                .city(str(data.get("city")))
                .state(str(data.get("state")))
                .data(allowedSubset(data, publicFields.keys()))
                .schemaVersion(publicFields.version())
                .build();
    }

    /**
     * The record, reduced to the keys the schema says this reader may have.
     *
     * <p>Built by walking the allowed keys rather than by filtering the record's entries: the
     * two produce the same map today, but only this direction stays correct if a record ever
     * carries a key the schema has since dropped — an unmapped leftover is exactly the kind of
     * value nobody has decided the audience of. See events-ui's recordAudit for the other half
     * of that story.
     */
    private Map<String, Object> allowedSubset(Map<String, Object> data, Set<String> allowed) {
        Map<String, Object> subset = new LinkedHashMap<>();
        for (String key : allowed) {
            Object value = data.get(key);
            if (value != null) subset.put(key, value);
        }
        return subset;
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
                        () -> {
                            Event e = events.get(m.getTenantId());
                            // Quietly rather than loudly, which is the opposite of get()'s policy
                            // and deliberately so: one unresolvable type would otherwise fail the
                            // whole list, taking every other event on it down with it. A card
                            // short of its title still links to a page that can explain itself.
                            return toResponse(e, m, readableData(e, m, fetchData(e)).orElse(Map.of()));
                        },
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

    /**
     * The record as this member may read it — the whole thing for a manager, and the fields of
     * the schema's guest-or-wider sections for everyone else.
     *
     * <p>This is the server's half of an answer that used to be given only by the client. The
     * section audience has always been carried on the schema and always been applied by events-ui,
     * but {@code toResponse} shipped the entire {@code data} map to every membership row — so a
     * guest's page hid the budget, the planning tasks and the event's own settings while the JSON
     * behind it carried all three. What a client chooses to render is a presentation decision;
     * what it is sent is this one.
     *
     * <p>Two rungs, not five. A manager reads HOST and needs no schema lookup to prove it, and
     * everybody else reads GUEST — including the INVITED and DECLINED rows the read path
     * deliberately admits, who see exactly what they saw before this existed. Narrowing an invitee
     * further is a product decision about what someone needs in order to answer an invitation, not
     * a leak to close, and it belongs with the work that gives them something to read instead.
     *
     * <p><strong>Status gates the rung, not role.</strong> {@code canManage()} answers only what
     * the row says the member <em>is</em>, and every write path pairs it with {@code requireMember}
     * for the other half — an invitation that has not been accepted is not membership. Read on its
     * own it would hand the whole record to someone invited to co-host and still deciding, which is
     * the same ordering events-ui's ladder is careful about for the same reason.
     *
     * <p>Empty {@code Optional} means the schema could not be resolved at all — not that nothing
     * is visible. The callers differ on what to do about it, which is why this returns the
     * distinction rather than resolving it here.
     */
    private Optional<Map<String, Object>> readableData(Event event, EventMember viewer, Map<String, Object> data) {
        if (viewer != null && viewer.isActive() && viewer.canManage()) return Optional.of(data);

        return schemaClient
                .visibleFields(event.getObjectType(), SchemaRegistryClient.GUEST_AUDIENCE)
                .map(fields -> allowedSubset(data, fields.keys()));
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
