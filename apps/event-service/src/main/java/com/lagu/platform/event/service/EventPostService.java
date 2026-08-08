package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.client.RecordServiceClient;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventPostLike;
import com.lagu.platform.event.domain.EventPostLikeRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Event social feed — replaces event-nest's posts-service. Posts/comments/reports are plain
 * schema-driven records (EVENT_POST/EVENT_COMMENT/EVENT_POST_REPORT); the one thing that
 * doesn't fit the Record model is likes (a User<->Record edge, and User isn't a Record here),
 * handled by the small local EventPostLikeRepository instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventPostService {

    private final EventRepository eventRepo;
    private final EventMemberRepository memberRepo;
    private final EventPostLikeRepository likeRepo;
    private final RecordServiceClient recordClient;

    @Transactional
    public PostResponse createPost(UUID eventId, UUID authorUserId, CreatePostRequest req) {
        Event event = requireEvent(eventId);
        requireMember(event, authorUserId);

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("post_content", req.getContent());
        if (req.getImageIds() != null) {
            data.put("post_image_ids", req.getImageIds().stream().map(UUID::toString).toList());
        }
        data.put("post_pinned", false);
        data.put("post_locked", false);

        Map<String, Object> recordResponse = recordClient.createRecord(event.getTenantId(), authorUserId, "EVENT_POST", data);
        UUID postId = recordClient.extractRecordId(recordResponse);
        if (postId == null) {
            throw new ValidationException("Failed to create post");
        }
        recordClient.createRelationship(event.getRecordId(), event.getTenantId(), authorUserId, "EVENT_POST", postId);

        boolean approvalRequired = approvalRequired(event);
        String trigger = approvalRequired ? "submit_for_approval" : "publish";
        recordClient.requestTransition(postId, event.getTenantId(), authorUserId, trigger);

        return PostResponse.builder()
                .id(postId).authorUserId(authorUserId).content(req.getContent())
                .imageIds(req.getImageIds()).pinned(false).locked(false)
                .status(approvalRequired ? "PENDING" : "PUBLISHED")
                .likeCount(0).likedByMe(false)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    /** How many of the requester's own unpublished posts to surface — this is a transient
     *  backlog (seconds, unless moderation is on), not a browsable collection. */
    private static final int OWN_UNPUBLISHED_LIMIT = 20;

    /**
     * The PUBLISHED feed, plus the requester's <em>own</em> posts that haven't got there yet.
     *
     * <p>Publishing runs through workflow-service asynchronously, so a post is DRAFT for a
     * moment after it's created (and stays PENDING for as long as moderation takes). Listing
     * only PUBLISHED meant an author watched their post vanish on the refetch that immediately
     * follows creation. Their own unpublished posts come back tagged with their real status so
     * the client can mark them; nobody else sees them.
     */
    public List<PostResponse> listPosts(UUID eventId, UUID requesterId, int page, int size) {
        Event event = requireEvent(eventId);
        requireMember(event, requesterId);

        List<PostResponse> published = recordClient
                .listRecords(event.getTenantId(), "EVENT_POST", "PUBLISHED", page, size).stream()
                .map(r -> toPostResponse(r, requesterId))
                .toList();

        // Only the first page carries them, so the PUBLISHED stream keeps exact page boundaries
        // — clients treat a short page as the end of the feed.
        if (page > 0) {
            return published;
        }

        List<PostResponse> ownUnpublished = Stream.of("DRAFT", "PENDING")
                .flatMap(status -> recordClient
                        .listRecords(event.getTenantId(), "EVENT_POST", status, 0, OWN_UNPUBLISHED_LIMIT).stream())
                .filter(r -> requesterId.toString().equals(String.valueOf(r.get("createdBy"))))
                .map(r -> toPostResponse(r, requesterId))
                .toList();

        if (ownUnpublished.isEmpty()) {
            return published;
        }
        List<PostResponse> combined = new ArrayList<>(ownUnpublished);
        combined.addAll(published);
        return combined;
    }

    public List<PostResponse> listPendingPosts(UUID eventId, UUID requesterId, int page, int size) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        return recordClient.listRecords(event.getTenantId(), "EVENT_POST", "PENDING", page, size).stream()
                .map(r -> toPostResponse(r, requesterId))
                .toList();
    }

    /**
     * Retires a post into the workflow's terminal REJECTED state.
     *
     * <p>This used to call deleteRecord, which could never succeed: record-service denies
     * DELETE on RECORD to internal service callers by policy (DefaultPermissionEvaluator grants
     * SVC_* callers CREATE/UPDATE/TRANSITION only), so every delete answered 403 — silently, in
     * the one place that swallowed the error. REJECTED is terminal and excluded from every
     * listing, including the author's own unpublished posts, so the post becomes invisible to
     * everyone. The transition is processed asynchronously by workflow-service.
     */
    @Transactional
    public void deletePost(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        EventMember member = requireMember(event, requesterId);
        Map<String, Object> record = getRecordOrNotFound(postId, event.getTenantId());
        requireAuthorOrManager(record, member, requesterId);
        recordClient.requestTransition(postId, event.getTenantId(), requesterId, "remove");
    }

    @Transactional
    public PostResponse togglePin(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        Map<String, Object> record = getRecordOrNotFound(postId, event.getTenantId());
        boolean current = Boolean.TRUE.equals(dataOf(record).get("post_pinned"));
        Map<String, Object> patched = recordClient.patchRecord(postId, event.getTenantId(), requesterId,
                Map.of("post_pinned", !current));
        return toPostResponse(unwrap(patched), requesterId);
    }

    @Transactional
    public PostResponse toggleLock(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        Map<String, Object> record = getRecordOrNotFound(postId, event.getTenantId());
        boolean current = Boolean.TRUE.equals(dataOf(record).get("post_locked"));
        Map<String, Object> patched = recordClient.patchRecord(postId, event.getTenantId(), requesterId,
                Map.of("post_locked", !current));
        return toPostResponse(unwrap(patched), requesterId);
    }

    @Transactional
    public void approvePost(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        recordClient.requestTransition(postId, event.getTenantId(), requesterId, "approve");
    }

    @Transactional
    public void rejectPost(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        recordClient.requestTransition(postId, event.getTenantId(), requesterId, "reject");
    }

    @Transactional
    public PostReportResponse reportPost(UUID eventId, UUID postId, UUID reporterId, CreateReportRequest req) {
        Event event = requireEvent(eventId);
        requireMember(event, reporterId);
        getRecordOrNotFound(postId, event.getTenantId()); // 404 if the post doesn't exist/isn't in this event

        Map<String, Object> data = Map.of(
                "reported_post_id", postId.toString(),
                "report_reason", req.getReason().toUpperCase(),
                "report_details", req.getDetails() != null ? req.getDetails() : "");
        Map<String, Object> recordResponse = recordClient.createRecord(event.getTenantId(), reporterId, "EVENT_POST_REPORT", data);
        UUID reportId = recordClient.extractRecordId(recordResponse);
        if (reportId == null) {
            throw new ValidationException("Failed to submit report");
        }
        return PostReportResponse.builder()
                .id(reportId).postId(postId).reporterUserId(reporterId)
                .reason(req.getReason().toUpperCase()).details(req.getDetails())
                .createdAt(OffsetDateTime.now())
                .build();
    }

    /**
     * Open reports — those whose post still exists.
     *
     * <p>Removing the offending post is how a moderator resolves a report, but the
     * EVENT_POST_REPORT record outlives the post it points at. Returning those too meant the
     * queue never emptied and every handled report stayed on screen looking outstanding.
     */
    public List<PostReportResponse> listReportedPosts(UUID eventId, UUID requesterId, int page, int size) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        List<PostReportResponse> reports = recordClient
                .listRecords(event.getTenantId(), "EVENT_POST_REPORT", null, page, size).stream()
                .map(this::toReportResponse)
                .toList();

        // Cached per post id — one post commonly draws several reports, and each miss is a
        // round trip to record-service.
        Map<UUID, Boolean> stillOpen = new HashMap<>();
        return reports.stream()
                .filter(r -> r.getPostId() != null && stillOpen.computeIfAbsent(
                        r.getPostId(), id -> isPostLive(id, event.getTenantId())))
                .toList();
    }

    /**
     * Whether a reported post is still live, and so its report still open.
     *
     * <p>Status, not mere existence: removing a post retires it to the terminal REJECTED state
     * rather than deleting the record (see deletePost), so the record outlives the removal and
     * an existence check would leave every handled report sitting in the queue.
     */
    private boolean isPostLive(UUID postId, UUID tenantId) {
        Map<String, Object> record = unwrap(recordClient.getRecord(postId, tenantId));
        return !record.isEmpty() && !"REJECTED".equals(String.valueOf(record.get("status")));
    }

    @Transactional
    public PostResponse toggleLike(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireMember(event, requesterId);
        Map<String, Object> record = getRecordOrNotFound(postId, event.getTenantId());

        if (likeRepo.existsByPostRecordIdAndUserId(postId, requesterId)) {
            likeRepo.deleteByPostRecordIdAndUserId(postId, requesterId);
        } else {
            likeRepo.save(new EventPostLike(postId, requesterId));
        }
        PostResponse response = toPostResponse(record, requesterId);
        response.setLikeCount(likeRepo.countByPostRecordId(postId));
        response.setLikedByMe(likeRepo.existsByPostRecordIdAndUserId(postId, requesterId));
        return response;
    }

    // ── comments ─────────────────────────────────────────────────────────────

    @Transactional
    public CommentResponse addComment(UUID eventId, UUID postId, UUID authorUserId, CreateCommentRequest req) {
        Event event = requireEvent(eventId);
        requireMember(event, authorUserId);
        Map<String, Object> post = getRecordOrNotFound(postId, event.getTenantId());
        if (Boolean.TRUE.equals(dataOf(post).get("post_locked"))) {
            throw new ValidationException("Comments are locked on this post");
        }

        Map<String, Object> recordResponse = recordClient.createRecord(event.getTenantId(), authorUserId,
                "EVENT_COMMENT", Map.of("comment_content", req.getContent()));
        UUID commentId = recordClient.extractRecordId(recordResponse);
        if (commentId == null) {
            throw new ValidationException("Failed to add comment");
        }
        recordClient.createRelationship(postId, event.getTenantId(), authorUserId, "EVENT_COMMENT", commentId);
        // New record starts DRAFT; comments aren't moderated pre-publish, so move it straight
        // to the workflow's "no case" -- there is no EVENT_COMMENT workflow at all, so its
        // status just stays DRAFT internally. That's fine: comments aren't queried by status.
        return CommentResponse.builder()
                .id(commentId).authorUserId(authorUserId).content(req.getContent())
                .createdAt(OffsetDateTime.now())
                .build();
    }

    public List<CommentResponse> listComments(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireMember(event, requesterId);
        return recordClient.listRelationships(postId, event.getTenantId()).stream()
                .filter(rel -> "EVENT_COMMENT".equals(rel.get("relationshipName")))
                .map(rel -> {
                    UUID targetId = UUID.fromString(String.valueOf(rel.get("targetRecordId")));
                    Map<String, Object> comment = recordClient.getRecord(targetId, event.getTenantId());
                    return toCommentResponse(unwrap(comment));
                })
                .toList();
    }

    /**
     * Detaches the comment from its post.
     *
     * <p>Like deletePost this can't call deleteRecord — internal service callers are denied
     * DELETE on RECORD (DefaultPermissionEvaluator). Posts solve that with a workflow
     * transition to REJECTED, but EVENT_COMMENT deliberately has no workflow (see addComment),
     * and inventing one for a single action would be disproportionate. listComments resolves
     * comments purely by walking the post's EVENT_COMMENT relationships, so dropping the edge
     * removes it from every listing — and unlike a transition it takes effect immediately,
     * since relationship writes count as RECORD UPDATE rather than going through Kafka.
     *
     * <p>The comment record itself is left orphaned and unreachable, the same trade the post
     * path makes by keeping REJECTED records around.
     */
    @Transactional
    public void deleteComment(UUID eventId, UUID postId, UUID commentId, UUID requesterId) {
        Event event = requireEvent(eventId);
        EventMember member = requireMember(event, requesterId);
        Map<String, Object> comment = getRecordOrNotFound(commentId, event.getTenantId());
        requireAuthorOrManager(comment, member, requesterId);
        recordClient.deleteRelationship(postId, event.getTenantId(), "EVENT_COMMENT", commentId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean approvalRequired(Event event) {
        Map<String, Object> eventRecord = recordClient.getRecord(event.getRecordId(), event.getTenantId());
        Object flag = dataOf(unwrap(eventRecord)).get("post_approval_required");
        return Boolean.TRUE.equals(flag);
    }

    private Map<String, Object> getRecordOrNotFound(UUID recordId, UUID tenantId) {
        Map<String, Object> response = recordClient.getRecord(recordId, tenantId);
        Map<String, Object> record = unwrap(response);
        if (record.isEmpty()) {
            throw new ResourceNotFoundException("Post", recordId.toString());
        }
        return record;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrap(Map<String, Object> apiResponse) {
        if (apiResponse == null) return Map.of();
        Object data = apiResponse.get("data");
        return data instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Map<String, Object> record) {
        Object data = record.get("data");
        return data instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private void requireAuthorOrManager(Map<String, Object> record, EventMember member, UUID requesterId) {
        Object createdBy = record.get("createdBy");
        boolean isAuthor = createdBy != null && requesterId.toString().equals(String.valueOf(createdBy));
        if (!isAuthor && !member.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author or an event moderator can do this");
        }
    }

    private Event requireEvent(UUID eventId) {
        return eventRepo.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId.toString()));
    }

    private EventMember requireMember(Event event, UUID userId) {
        EventMember member = memberRepo.findByTenantIdAndUserId(event.getTenantId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this event"));
        if (!"ACCEPTED".equals(member.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Membership not accepted");
        }
        return member;
    }

    private EventMember requireManager(Event event, UUID userId) {
        EventMember member = requireMember(event, userId);
        if (!member.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN or MAINTAINER role required");
        }
        return member;
    }

    @SuppressWarnings("unchecked")
    private PostResponse toPostResponse(Map<String, Object> record, UUID viewerId) {
        // `record` is always an already-unwrapped RecordResponse map here (id/status/data/
        // createdBy at the top level) -- both listRecords' content items and unwrap()'s output
        // have that shape.
        Map<String, Object> fields = dataOf(record);
        UUID id = UUID.fromString(String.valueOf(record.get("id")));
        Object imageIdsRaw = fields.get("post_image_ids");
        List<UUID> imageIds = imageIdsRaw instanceof List<?> list
                ? list.stream().map(v -> UUID.fromString(String.valueOf(v))).toList() : null;

        return PostResponse.builder()
                .id(id)
                .authorUserId(record.get("createdBy") != null ? UUID.fromString(String.valueOf(record.get("createdBy"))) : null)
                .content((String) fields.get("post_content"))
                .imageIds(imageIds)
                .pinned(Boolean.TRUE.equals(fields.get("post_pinned")))
                .locked(Boolean.TRUE.equals(fields.get("post_locked")))
                .status((String) record.get("status"))
                .likeCount(likeRepo.countByPostRecordId(id))
                .likedByMe(likeRepo.existsByPostRecordIdAndUserId(id, viewerId))
                .createdAt(createdAtOf(record))
                .build();
    }

    @SuppressWarnings("unchecked")
    private CommentResponse toCommentResponse(Map<String, Object> record) {
        Map<String, Object> fields = record.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return CommentResponse.builder()
                .id(UUID.fromString(String.valueOf(record.get("id"))))
                .authorUserId(record.get("createdBy") != null ? UUID.fromString(String.valueOf(record.get("createdBy"))) : null)
                .content((String) fields.get("comment_content"))
                .createdAt(createdAtOf(record))
                .build();
    }

    @SuppressWarnings("unchecked")
    private PostReportResponse toReportResponse(Map<String, Object> record) {
        Map<String, Object> fields = record.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return PostReportResponse.builder()
                .id(UUID.fromString(String.valueOf(record.get("id"))))
                .postId(fields.get("reported_post_id") != null ? UUID.fromString(String.valueOf(fields.get("reported_post_id"))) : null)
                .reporterUserId(record.get("createdBy") != null ? UUID.fromString(String.valueOf(record.get("createdBy"))) : null)
                .reason((String) fields.get("report_reason"))
                .details((String) fields.get("report_details"))
                .createdAt(createdAtOf(record))
                .build();
    }

    /**
     * RecordResponse.createdAt arrives as an ISO-8601 string in these untyped record maps (they
     * come off the wire as generic JSON), so it needs parsing rather than a cast. Without this
     * every listed post/comment/report carried a null timestamp while only the just-created
     * ones -- built in-process above -- had one.
     */
    private OffsetDateTime createdAtOf(Map<String, Object> record) {
        Object raw = record.get("createdAt");
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(String.valueOf(raw));
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("Unparseable createdAt '{}' on record {}", raw, record.get("id"));
            return null;
        }
    }
}
