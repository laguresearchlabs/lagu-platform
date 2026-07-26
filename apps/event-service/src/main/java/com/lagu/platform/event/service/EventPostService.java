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
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

        Map<String, Object> recordResponse = recordClient.createRecord(event.getOrgId(), authorUserId, "EVENT_POST", data);
        UUID postId = recordClient.extractRecordId(recordResponse);
        if (postId == null) {
            throw new ValidationException("Failed to create post");
        }
        recordClient.createRelationship(event.getRecordId(), event.getOrgId(), authorUserId, "EVENT_POST", postId);

        boolean approvalRequired = approvalRequired(event);
        String trigger = approvalRequired ? "submit_for_approval" : "publish";
        recordClient.requestTransition(postId, event.getOrgId(), authorUserId, trigger);

        return PostResponse.builder()
                .id(postId).authorUserId(authorUserId).content(req.getContent())
                .imageIds(req.getImageIds()).pinned(false).locked(false)
                .status(approvalRequired ? "PENDING" : "PUBLISHED")
                .likeCount(0).likedByMe(false)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    public List<PostResponse> listPosts(UUID eventId, UUID requesterId, int page, int size) {
        Event event = requireEvent(eventId);
        requireMember(event, requesterId);
        return recordClient.listRecords(event.getOrgId(), "EVENT_POST", "PUBLISHED", page, size).stream()
                .map(r -> toPostResponse(r, requesterId))
                .toList();
    }

    public List<PostResponse> listPendingPosts(UUID eventId, UUID requesterId, int page, int size) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        return recordClient.listRecords(event.getOrgId(), "EVENT_POST", "PENDING", page, size).stream()
                .map(r -> toPostResponse(r, requesterId))
                .toList();
    }

    @Transactional
    public void deletePost(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        EventMember member = requireMember(event, requesterId);
        Map<String, Object> record = getRecordOrNotFound(postId, event.getOrgId());
        requireAuthorOrManager(record, member, requesterId);
        recordClient.deleteRecord(postId, event.getOrgId());
    }

    @Transactional
    public PostResponse togglePin(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        Map<String, Object> record = getRecordOrNotFound(postId, event.getOrgId());
        boolean current = Boolean.TRUE.equals(dataOf(record).get("post_pinned"));
        Map<String, Object> patched = recordClient.patchRecord(postId, event.getOrgId(), requesterId,
                Map.of("post_pinned", !current));
        return toPostResponse(unwrap(patched), requesterId);
    }

    @Transactional
    public PostResponse toggleLock(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        Map<String, Object> record = getRecordOrNotFound(postId, event.getOrgId());
        boolean current = Boolean.TRUE.equals(dataOf(record).get("post_locked"));
        Map<String, Object> patched = recordClient.patchRecord(postId, event.getOrgId(), requesterId,
                Map.of("post_locked", !current));
        return toPostResponse(unwrap(patched), requesterId);
    }

    @Transactional
    public void approvePost(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        recordClient.requestTransition(postId, event.getOrgId(), requesterId, "approve");
    }

    @Transactional
    public void rejectPost(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        recordClient.requestTransition(postId, event.getOrgId(), requesterId, "reject");
    }

    @Transactional
    public PostReportResponse reportPost(UUID eventId, UUID postId, UUID reporterId, CreateReportRequest req) {
        Event event = requireEvent(eventId);
        requireMember(event, reporterId);
        getRecordOrNotFound(postId, event.getOrgId()); // 404 if the post doesn't exist/isn't in this event

        Map<String, Object> data = Map.of(
                "reported_post_id", postId.toString(),
                "report_reason", req.getReason().toUpperCase(),
                "report_details", req.getDetails() != null ? req.getDetails() : "");
        Map<String, Object> recordResponse = recordClient.createRecord(event.getOrgId(), reporterId, "EVENT_POST_REPORT", data);
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

    public List<PostReportResponse> listReportedPosts(UUID eventId, UUID requesterId, int page, int size) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        return recordClient.listRecords(event.getOrgId(), "EVENT_POST_REPORT", null, page, size).stream()
                .map(this::toReportResponse)
                .toList();
    }

    @Transactional
    public PostResponse toggleLike(UUID eventId, UUID postId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireMember(event, requesterId);
        Map<String, Object> record = getRecordOrNotFound(postId, event.getOrgId());

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
        Map<String, Object> post = getRecordOrNotFound(postId, event.getOrgId());
        if (Boolean.TRUE.equals(dataOf(post).get("post_locked"))) {
            throw new ValidationException("Comments are locked on this post");
        }

        Map<String, Object> recordResponse = recordClient.createRecord(event.getOrgId(), authorUserId,
                "EVENT_COMMENT", Map.of("comment_content", req.getContent()));
        UUID commentId = recordClient.extractRecordId(recordResponse);
        if (commentId == null) {
            throw new ValidationException("Failed to add comment");
        }
        recordClient.createRelationship(postId, event.getOrgId(), authorUserId, "EVENT_COMMENT", commentId);
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
        return recordClient.listRelationships(postId, event.getOrgId()).stream()
                .filter(rel -> "EVENT_COMMENT".equals(rel.get("relationshipName")))
                .map(rel -> {
                    UUID targetId = UUID.fromString(String.valueOf(rel.get("targetRecordId")));
                    Map<String, Object> comment = recordClient.getRecord(targetId, event.getOrgId());
                    return toCommentResponse(unwrap(comment));
                })
                .toList();
    }

    @Transactional
    public void deleteComment(UUID eventId, UUID postId, UUID commentId, UUID requesterId) {
        Event event = requireEvent(eventId);
        EventMember member = requireMember(event, requesterId);
        Map<String, Object> comment = getRecordOrNotFound(commentId, event.getOrgId());
        requireAuthorOrManager(comment, member, requesterId);
        recordClient.deleteRecord(commentId, event.getOrgId());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean approvalRequired(Event event) {
        Map<String, Object> eventRecord = recordClient.getRecord(event.getRecordId(), event.getOrgId());
        Object flag = dataOf(unwrap(eventRecord)).get("post_approval_required");
        return Boolean.TRUE.equals(flag);
    }

    private Map<String, Object> getRecordOrNotFound(UUID recordId, UUID orgId) {
        Map<String, Object> response = recordClient.getRecord(recordId, orgId);
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
        EventMember member = memberRepo.findByOrgIdAndUserId(event.getOrgId(), userId)
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
                .build();
    }

    @SuppressWarnings("unchecked")
    private CommentResponse toCommentResponse(Map<String, Object> record) {
        Map<String, Object> fields = record.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return CommentResponse.builder()
                .id(UUID.fromString(String.valueOf(record.get("id"))))
                .authorUserId(record.get("createdBy") != null ? UUID.fromString(String.valueOf(record.get("createdBy"))) : null)
                .content((String) fields.get("comment_content"))
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
                .build();
    }
}
