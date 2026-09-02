package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.event.client.RecordServiceClient;
import com.lagu.platform.event.client.SchemaRegistryClient;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.dto.CreateEventRequest;
import com.lagu.platform.event.dto.TransitionRequest;
import com.lagu.platform.event.dto.UpdateEventRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * EventService takes the acting userId as an explicit method parameter rather than reading
 * GatewayHeaderFilter's ThreadLocal internally (that only happens in EventController), so this
 * is a plain unit test — no Spring context, no request/filter chain needed.
 *
 * <p>Note: Event.id doubles as the org-partition key (Event.getTenantId() just returns id — see
 * Event.java) — there's no separate tenantId to track in these tests, `eventId` is used everywhere.
 */
class EventServiceTest {

    private final EventRepository eventRepo = mock(EventRepository.class);
    private final EventMemberRepository memberRepo = mock(EventMemberRepository.class);
    private final RecordServiceClient recordClient = mock(RecordServiceClient.class);
    private final SchemaRegistryClient schemaClient = mock(SchemaRegistryClient.class);
    private final EventShareLinkService shareLinkService = mock(EventShareLinkService.class);
    private final EventService service =
            new EventService(eventRepo, memberRepo, recordClient, schemaClient, shareLinkService);

    private final UUID eventId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setId(eventId);
        event.setRecordId(recordId);
        event.setObjectType("BIRTHDAY_EVENT");
        event.setOwnerUserId(ownerId);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(event));
        // listMine() batch-loads its events rather than one findById per membership row.
        when(eventRepo.findAllById(any())).thenReturn(java.util.List.of(event));
        // Where every listing type starts: no section marked PUBLIC, so no data is shareable.
        when(schemaClient.publicFields(any())).thenReturn(new SchemaRegistryClient.PublicFields(Set.of(), null));
    }

    private EventMember memberWithRole(UUID userId, String role, String status) {
        EventMember m = new EventMember();
        m.setTenantId(eventId);
        m.setUserId(userId);
        m.setRole(role);
        m.setStatus(status);
        return m;
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Test
    void createFailsWhenRecordServiceReturnsNoRecordId() {
        when(recordClient.createRecord(any(), eq(ownerId), eq("BIRTHDAY_EVENT"), any())).thenReturn(null);
        when(recordClient.extractRecordId(null)).thenReturn(null);

        CreateEventRequest req = new CreateEventRequest();
        req.setObjectType("BIRTHDAY_EVENT");
        req.setData(Map.of("title", "Test"));

        assertThatThrownBy(() -> service.create(req, ownerId))
                .hasMessageContaining("Failed to create");
        verify(eventRepo, never()).save(any());
    }

    @Test
    void createPersistsEventAndOwnerAdminMember() {
        Map<String, Object> recordResponse = Map.of("data", Map.of("id", recordId.toString()));
        when(recordClient.createRecord(any(), eq(ownerId), eq("BIRTHDAY_EVENT"), any())).thenReturn(recordResponse);
        when(recordClient.extractRecordId(recordResponse)).thenReturn(recordId);
        when(recordClient.getRecord(any(), any())).thenReturn(Map.of());

        CreateEventRequest req = new CreateEventRequest();
        req.setObjectType("BIRTHDAY_EVENT");
        req.setData(Map.of("title", "Test"));

        service.create(req, ownerId);

        ArgumentCaptor<Event> savedEvent = ArgumentCaptor.forClass(Event.class);
        verify(eventRepo).save(savedEvent.capture());
        Event e = savedEvent.getValue();
        assertThat(e.getRecordId()).isEqualTo(recordId);
        assertThat(e.getObjectType()).isEqualTo("BIRTHDAY_EVENT");
        assertThat(e.getOwnerUserId()).isEqualTo(ownerId);
        // the core invariant this refactor introduced: no separate tenantId, id doubles as it.
        assertThat(e.getTenantId()).isEqualTo(e.getId());

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(ownerId)
                && "ADMIN".equals(m.getRole()) && "ACCEPTED".equals(m.getStatus())));

        // the value handed to record-service as the tenancy/org key must be the same id the
        // saved Event ends up with, not some other freshly-minted UUID.
        verify(recordClient).createRecord(eq(e.getId()), eq(ownerId), eq("BIRTHDAY_EVENT"), any());
    }

    // ── listMine() ───────────────────────────────────────────────────────────

    @Test
    void listMineIncludesInvitedNotYetAcceptedMemberships() {
        EventMember invited = memberWithRole(ownerId, "INVITEE", "INVITED");
        when(memberRepo.findByUserId(ownerId)).thenReturn(java.util.List.of(invited));

        var results = service.listMine(ownerId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMyRole()).isEqualTo("INVITEE");
    }

    @Test
    void listMineHydratesEachRowWithItsRecordData() {
        // The rows used to come back with a null `data`, and the client compensated by
        // re-fetching every event one at a time.
        when(memberRepo.findByUserId(ownerId))
                .thenReturn(java.util.List.of(memberWithRole(ownerId, "ADMIN", "ACCEPTED")));
        when(recordClient.getRecord(recordId, eventId))
                .thenReturn(Map.of("data", Map.of("data", Map.of("name", "Priya's 30th"))));

        var results = service.listMine(ownerId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getData()).containsEntry("name", "Priya's 30th");
    }

    @Test
    void listMineExcludesDeclinedMemberships() {
        EventMember declined = memberWithRole(ownerId, "INVITEE", "DECLINED");
        when(memberRepo.findByUserId(ownerId)).thenReturn(java.util.List.of(declined));

        assertThat(service.listMine(ownerId)).isEmpty();
    }

    // ── get() ────────────────────────────────────────────────────────────────

    @Test
    void getThrowsNotFoundForUnknownEvent() {
        UUID unknownId = UUID.randomUUID();
        when(eventRepo.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(unknownId, ownerId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getThrowsForbiddenForNonMember() {
        UUID strangerId = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(eventId, strangerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(eventId, strangerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getStillRefusesANonMemberOfAnEventMarkedPublic() {
        // This used to succeed, and that is exactly what made share links unrevocable: access
        // came from the record's own visibility field, so the link was never the gate and closing
        // it changed nothing. A non-member now gets in by redeeming a live link — which makes
        // them a member — and by no other route.
        UUID strangerId = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(eventId, strangerId)).thenReturn(Optional.empty());
        when(recordClient.getRecord(recordId, eventId))
                .thenReturn(Map.of("data", Map.of("data", Map.of("visibility", "PUBLIC"))));

        assertThatThrownBy(() -> service.get(eventId, strangerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void sharePreviewReturnsCardFieldsForALiveToken() {
        stubToken("live-token");
        when(recordClient.getRecord(recordId, eventId)).thenReturn(Map.of("data", Map.of("data", Map.of(
                "visibility", "UNLISTED",
                "name", "Aarav's 5th Birthday",
                "description", "Cake at 4pm",
                "cover_image", "https://cdn.example.com/cover.jpg",
                "city", "Bengaluru",
                "is_virtual", true))));

        var preview = service.getSharePreview("live-token");

        assertThat(preview.getTitle()).isEqualTo("Aarav's 5th Birthday");
        assertThat(preview.getDescription()).isEqualTo("Cake at 4pm");
        assertThat(preview.getCoverImage()).isEqualTo("https://cdn.example.com/cover.jpg");
        assertThat(preview.getCity()).isEqualTo("Bengaluru");
        // Absent and non-string values are dropped, never coerced into the card.
        assertThat(preview.getState()).isNull();
        assertThat(preview.getStartDatetime()).isNull();
    }

    @Test
    void sharePreviewIsNotFoundForADeadToken() {
        // The token is the authorization now, so there is no visibility test here at all —
        // whoever minted the link decided this much could be shown. Resolution is what 404s, and
        // it makes revoked, expired, exhausted and never-issued indistinguishable.
        when(shareLinkService.resolve("dead-token"))
                .thenThrow(new ResourceNotFoundException("ShareLink", "token"));

        assertThatThrownBy(() -> service.getSharePreview("dead-token"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sharePreviewShowsAPrivateEventToAValidToken() {
        // The point of the change: a host can now show a private event to someone they invited,
        // instead of the recipient meeting a blank wall and a request queue.
        stubToken("private-token");
        when(recordClient.getRecord(recordId, eventId)).thenReturn(Map.of("data", Map.of("data", Map.of(
                "visibility", "PRIVATE", "name", "Secret"))));

        assertThat(service.getSharePreview("private-token").getTitle()).isEqualTo("Secret");
    }


    @Test
    void sharePreviewCarriesOnlyThePublicSectionsFields() {
        // The allow-list is built from the schema and nothing else, so what a stranger holding a
        // forwarded link can read is a decision an admin made in the Admin Portal — not one this
        // service, or the shape of a record, made for them.
        stubToken("live-token");
        when(schemaClient.publicFields("BIRTHDAY_EVENT"))
                .thenReturn(new SchemaRegistryClient.PublicFields(Set.of("name", "cake_preference"), 3));
        when(recordClient.getRecord(recordId, eventId)).thenReturn(Map.of("data", Map.of("data", Map.of(
                "name", "Aarav's 5th Birthday",
                "cake_preference", "Chocolate truffle",
                "total_budget", 250000,
                "guest_phone_numbers", "…"))));

        var preview = service.getSharePreview("live-token");

        assertThat(preview.getData()).containsOnlyKeys("name", "cake_preference");
        assertThat(preview.getSchemaVersion()).isEqualTo(3);
    }

    @Test
    void sharePreviewCarriesNoDataWhenTheSchemaCannotBeResolved() {
        // publicFields fails closed and hands back an empty set. The preview must degrade to the
        // hand-picked card scalars rather than publishing the record: the opposite default would
        // leak an entire event the first time a deploy raced a schema-registry restart.
        stubToken("live-token");
        when(recordClient.getRecord(recordId, eventId)).thenReturn(Map.of("data", Map.of("data", Map.of(
                "name", "Aarav's 5th Birthday",
                "total_budget", 250000))));

        var preview = service.getSharePreview("live-token");

        assertThat(preview.getData()).isEmpty();
        assertThat(preview.getSchemaVersion()).isNull();
        // The card a crawler needs still renders.
        assertThat(preview.getTitle()).isEqualTo("Aarav's 5th Birthday");
    }

    @Test
    void sharePreviewSkipsAPublicFieldTheRecordDoesNotCarry() {
        // Walking the allowed keys rather than filtering the record's entries means a key the
        // schema declares but the host never filled in is simply absent, not a null the client
        // has to render around.
        stubToken("live-token");
        when(schemaClient.publicFields("BIRTHDAY_EVENT"))
                .thenReturn(new SchemaRegistryClient.PublicFields(Set.of("name", "dresscode"), 1));
        when(recordClient.getRecord(recordId, eventId))
                .thenReturn(Map.of("data", Map.of("data", Map.of("name", "Aarav's 5th Birthday"))));

        assertThat(service.getSharePreview("live-token").getData()).containsOnlyKeys("name");
    }

    /** Points a token at this test's event, the way the anonymous preview path resolves one. */
    private void stubToken(String token) {
        var link = new com.lagu.platform.event.domain.EventShareLink();
        link.setId(UUID.randomUUID());
        link.setEventId(eventId);
        when(shareLinkService.resolve(token)).thenReturn(link);
    }

    @Test
    void getSucceedsForInvitedButNotYetAcceptedMember() {
        // An invited (not yet accepted) member must be able to view the event well enough to
        // decide whether to accept — there's no other endpoint that lets them discover what
        // they're being invited to first (see EventService.get()'s requireAnyMembership).
        UUID invitedId = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(eventId, invitedId))
                .thenReturn(Optional.of(memberWithRole(invitedId, "INVITEE", "INVITED")));
        when(recordClient.getRecord(recordId, eventId)).thenReturn(Map.of());

        var response = service.get(eventId, invitedId);

        assertThat(response.getMyRole()).isEqualTo("INVITEE");
    }

    @Test
    void getSucceedsForAcceptedInvitee() {
        when(memberRepo.findByTenantIdAndUserId(eventId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "INVITEE", "ACCEPTED")));
        when(recordClient.getRecord(recordId, eventId)).thenReturn(Map.of("data", Map.of("data", Map.of("title", "x"))));

        var response = service.get(eventId, ownerId);

        assertThat(response.getMyRole()).isEqualTo("INVITEE");
        assertThat(response.getData()).containsEntry("title", "x");
    }

    // ── update() / transition() require ADMIN or MAINTAINER ────────────────────

    @Test
    void updateRejectsPlainInvitee() {
        when(memberRepo.findByTenantIdAndUserId(eventId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "INVITEE", "ACCEPTED")));
        UpdateEventRequest req = new UpdateEventRequest();
        req.setData(Map.of("title", "New"));

        assertThatThrownBy(() -> service.update(eventId, ownerId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(recordClient, never()).updateRecord(any(), any(), any(), any());
    }

    @Test
    void updateSucceedsForMaintainer() {
        when(memberRepo.findByTenantIdAndUserId(eventId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "MAINTAINER", "ACCEPTED")));
        when(recordClient.updateRecord(eq(recordId), eq(eventId), eq(ownerId), any()))
                .thenReturn(Map.of("data", Map.of("data", Map.of("title", "New"))));

        UpdateEventRequest req = new UpdateEventRequest();
        req.setData(Map.of("title", "New"));

        var response = service.update(eventId, ownerId, req);
        assertThat(response.getData()).containsEntry("title", "New");
    }

    @Test
    void transitionRejectsPlainInvitee() {
        when(memberRepo.findByTenantIdAndUserId(eventId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "INVITEE", "ACCEPTED")));
        TransitionRequest req = new TransitionRequest();
        req.setTrigger("confirm");

        assertThatThrownBy(() -> service.requestTransition(eventId, ownerId, req))
                .isInstanceOf(ResponseStatusException.class);
        verify(recordClient, never()).requestTransition(any(), any(), any(), any());
    }

    @Test
    void transitionSucceedsForAdmin() {
        when(memberRepo.findByTenantIdAndUserId(eventId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "ADMIN", "ACCEPTED")));
        TransitionRequest req = new TransitionRequest();
        req.setTrigger("confirm");

        service.requestTransition(eventId, ownerId, req);

        verify(recordClient).requestTransition(recordId, eventId, ownerId, "confirm");
    }
}
