package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.client.RecordServiceClient;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventPostLikeRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.dto.CreateCommentRequest;
import com.lagu.platform.event.dto.CreatePostRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * The riskiest new logic in the social-feed rebuild: moderator-only actions (pin/lock/approve/
 * reject/pending/reported) must reject plain members, deletes must allow author-or-moderator
 * only, and locked posts must reject new comments.
 */
class EventPostServiceTest {

    private final EventRepository eventRepo = mock(EventRepository.class);
    private final EventMemberRepository memberRepo = mock(EventMemberRepository.class);
    private final EventPostLikeRepository likeRepo = mock(EventPostLikeRepository.class);
    private final RecordServiceClient recordClient = mock(RecordServiceClient.class);
    // A real guard over the same mocked repositories, not a mock of it: the membership rules
    // (accepted-only, manager-only) are part of what these tests assert, and stubbing them out
    // would leave the assertions checking nothing.
    private final EventMembershipGuard membership = new EventMembershipGuard(eventRepo, memberRepo);
    private final EventPostService service =
            new EventPostService(membership, eventRepo, memberRepo, likeRepo, recordClient);

    private final UUID eventId = UUID.randomUUID();
    // Event.id doubles as the org-partition key now (see Event.java) — alias kept so this file's
    // existing "tenantId" naming for EventMember lookups didn't need a sweeping rename.
    private final UUID tenantId = eventId;
    private final UUID recordId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Event event = new Event();
        event.setId(eventId);
        event.setRecordId(recordId);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(event));
    }

    private EventMember memberWithRole(UUID userId, String role) {
        EventMember m = new EventMember();
        m.setTenantId(tenantId);
        m.setUserId(userId);
        m.setRole(role);
        m.setStatus("ACCEPTED");
        return m;
    }

    private Map<String, Object> postRecord(UUID authorId, boolean locked) {
        return Map.of("id", postId.toString(), "status", "PUBLISHED", "createdBy", authorId.toString(),
                "data", Map.of("post_content", "hi", "post_pinned", false, "post_locked", locked));
    }

    @Test
    void togglePinRejectsPlainMember() {
        UUID member = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, member))
                .thenReturn(Optional.of(memberWithRole(member, "INVITEE")));

        assertThatThrownBy(() -> service.togglePin(eventId, postId, member))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(recordClient, never()).patchRecord(any(), any(), any(), any());
    }

    @Test
    void togglePinSucceedsForAdmin() {
        UUID admin = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, admin))
                .thenReturn(Optional.of(memberWithRole(admin, "ADMIN")));
        when(recordClient.getRecord(postId, tenantId)).thenReturn(Map.of("data", postRecord(admin, false)));
        when(recordClient.patchRecord(eq(postId), eq(tenantId), eq(admin), eq(Map.of("post_pinned", true))))
                .thenReturn(Map.of("data", postRecord(admin, false)));

        service.togglePin(eventId, postId, admin);

        verify(recordClient).patchRecord(postId, tenantId, admin, Map.of("post_pinned", true));
    }

    @Test
    void approveRejectsPlainMember() {
        UUID member = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, member))
                .thenReturn(Optional.of(memberWithRole(member, "INVITEE")));

        assertThatThrownBy(() -> service.approvePost(eventId, postId, member))
                .isInstanceOf(ResponseStatusException.class);
        verify(recordClient, never()).requestTransition(any(), any(), any(), any());
    }

    @Test
    void listPendingRejectsPlainMember() {
        UUID member = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, member))
                .thenReturn(Optional.of(memberWithRole(member, "INVITEE")));

        assertThatThrownBy(() -> service.listPendingPosts(eventId, member, 0, 20))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deletePostRejectsNonAuthorNonModerator() {
        UUID author = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, stranger))
                .thenReturn(Optional.of(memberWithRole(stranger, "INVITEE")));
        when(recordClient.getRecord(postId, tenantId)).thenReturn(Map.of("data", postRecord(author, false)));

        assertThatThrownBy(() -> service.deletePost(eventId, postId, stranger))
                .isInstanceOf(ResponseStatusException.class);
        verify(recordClient, never()).deleteRecord(any(), any());
    }

    // Deletion retires the post to REJECTED via the workflow instead of destroying the record.
    // These previously asserted deleteRecord, which record-service refuses for internal service
    // callers (DefaultPermissionEvaluator grants SVC_* CREATE/UPDATE/TRANSITION only) — so the
    // behaviour they locked in was one that answered 403 every time it ran for real.

    @Test
    void deletePostRetiresThePostForAuthor() {
        UUID author = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, author))
                .thenReturn(Optional.of(memberWithRole(author, "INVITEE")));
        when(recordClient.getRecord(postId, tenantId)).thenReturn(Map.of("data", postRecord(author, false)));

        service.deletePost(eventId, postId, author);

        verify(recordClient).requestTransition(postId, tenantId, author, "remove");
        verify(recordClient, never()).deleteRecord(any(), any());
    }

    @Test
    void deletePostRetiresThePostForModeratorEvenIfNotAuthor() {
        UUID author = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, admin))
                .thenReturn(Optional.of(memberWithRole(admin, "MAINTAINER")));
        when(recordClient.getRecord(postId, tenantId)).thenReturn(Map.of("data", postRecord(author, false)));

        service.deletePost(eventId, postId, admin);

        verify(recordClient).requestTransition(postId, tenantId, admin, "remove");
        verify(recordClient, never()).deleteRecord(any(), any());
    }

    @Test
    void deleteCommentDetachesItFromThePost() {
        // EVENT_COMMENT has no workflow to transition through, so removal drops the post's
        // relationship instead — listComments resolves comments by walking those edges, and
        // relationship writes are RECORD UPDATE, which internal callers are allowed.
        UUID author = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, author))
                .thenReturn(Optional.of(memberWithRole(author, "INVITEE")));
        when(recordClient.getRecord(commentId, tenantId)).thenReturn(Map.of("data",
                Map.of("id", commentId.toString(), "createdBy", author.toString(),
                        "data", Map.of("comment_content", "hi"))));

        service.deleteComment(eventId, postId, commentId, author);

        verify(recordClient).deleteRelationship(postId, tenantId, "EVENT_COMMENT", commentId);
        verify(recordClient, never()).deleteRecord(any(), any());
    }

    @Test
    void deleteCommentRejectedForAnotherMember() {
        UUID author = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, stranger))
                .thenReturn(Optional.of(memberWithRole(stranger, "INVITEE")));
        when(recordClient.getRecord(commentId, tenantId)).thenReturn(Map.of("data",
                Map.of("id", commentId.toString(), "createdBy", author.toString(),
                        "data", Map.of("comment_content", "hi"))));

        assertThatThrownBy(() -> service.deleteComment(eventId, postId, commentId, stranger))
                .isInstanceOf(ResponseStatusException.class);

        verify(recordClient, never()).deleteRelationship(any(), any(), any(), any());
    }

    @Test
    void addCommentRejectedWhenPostLocked() {
        UUID member = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, member))
                .thenReturn(Optional.of(memberWithRole(member, "INVITEE")));
        when(recordClient.getRecord(postId, tenantId)).thenReturn(Map.of("data", postRecord(author, true)));

        assertThatThrownBy(() -> service.addComment(eventId, postId, member, new CreateCommentRequest()))
                .isInstanceOf(ValidationException.class);
        verify(recordClient, never()).createRecord(any(), any(), eq("EVENT_COMMENT"), any());
    }

    @Test
    void toggleLikeTogglesOnAndOff() {
        UUID liker = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, liker))
                .thenReturn(Optional.of(memberWithRole(liker, "INVITEE")));
        when(recordClient.getRecord(postId, tenantId)).thenReturn(Map.of("data", postRecord(liker, false)));
        when(likeRepo.existsByPostRecordIdAndUserId(postId, liker)).thenReturn(false).thenReturn(true);

        service.toggleLike(eventId, postId, liker);

        verify(likeRepo).save(argThat(l -> l.getPostRecordId().equals(postId) && l.getUserId().equals(liker)));
        verify(likeRepo, never()).deleteByPostRecordIdAndUserId(any(), any());
    }

    @Test
    void createPostUsesPublishTriggerWhenApprovalNotRequired() {
        UUID author = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, author))
                .thenReturn(Optional.of(memberWithRole(author, "INVITEE")));
        Map<String, Object> createResponse = Map.of("data", Map.of("id", postId.toString()));
        when(recordClient.createRecord(eq(tenantId), eq(author), eq("EVENT_POST"), any())).thenReturn(createResponse);
        when(recordClient.extractRecordId(createResponse)).thenReturn(postId);
        when(recordClient.getRecord(recordId, tenantId)).thenReturn(Map.of("data", Map.of("data", Map.of())));

        CreatePostRequest req = new CreatePostRequest();
        req.setContent("hello");

        var response = service.createPost(eventId, author, req);

        assertThat(response.getStatus()).isEqualTo("PUBLISHED");
        verify(recordClient).requestTransition(postId, tenantId, author, "publish");
    }

    @Test
    void createPostUsesSubmitForApprovalTriggerWhenRequired() {
        UUID author = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, author))
                .thenReturn(Optional.of(memberWithRole(author, "INVITEE")));
        Map<String, Object> createResponse = Map.of("data", Map.of("id", postId.toString()));
        when(recordClient.createRecord(eq(tenantId), eq(author), eq("EVENT_POST"), any())).thenReturn(createResponse);
        when(recordClient.extractRecordId(createResponse)).thenReturn(postId);
        when(recordClient.getRecord(recordId, tenantId))
                .thenReturn(Map.of("data", Map.of("data", Map.of("post_approval_required", true))));

        CreatePostRequest req = new CreatePostRequest();
        req.setContent("hello");

        var response = service.createPost(eventId, author, req);

        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(recordClient).requestTransition(postId, tenantId, author, "submit_for_approval");
    }
}
