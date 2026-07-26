package com.lagu.platform.event.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.event.dto.*;
import com.lagu.platform.event.service.EventPostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/posts")
@RequiredArgsConstructor
public class EventPostController {

    private final EventPostService postService;

    @PostMapping
    public ResponseEntity<ApiResponse<PostResponse>> create(@PathVariable UUID eventId,
                                                             @Valid @RequestBody CreatePostRequest req) {
        PostResponse post = postService.createPost(eventId, EventController.requireUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(post));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PostResponse>>> list(@PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                postService.listPosts(eventId, EventController.requireUserId(), page, size)));
    }

    /** Moderators only — see EventPostService.listPendingPosts. */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<PostResponse>>> listPending(@PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                postService.listPendingPosts(eventId, EventController.requireUserId(), page, size)));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> delete(@PathVariable UUID eventId, @PathVariable UUID postId) {
        postService.deletePost(eventId, postId, EventController.requireUserId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{postId}/pin")
    public ResponseEntity<ApiResponse<PostResponse>> togglePin(@PathVariable UUID eventId, @PathVariable UUID postId) {
        return ResponseEntity.ok(ApiResponse.ok(
                postService.togglePin(eventId, postId, EventController.requireUserId())));
    }

    @PatchMapping("/{postId}/lock")
    public ResponseEntity<ApiResponse<PostResponse>> toggleLock(@PathVariable UUID eventId, @PathVariable UUID postId) {
        return ResponseEntity.ok(ApiResponse.ok(
                postService.toggleLock(eventId, postId, EventController.requireUserId())));
    }

    @PatchMapping("/{postId}/approve")
    public ResponseEntity<ApiResponse<Void>> approve(@PathVariable UUID eventId, @PathVariable UUID postId) {
        postService.approvePost(eventId, postId, EventController.requireUserId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/{postId}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID eventId, @PathVariable UUID postId) {
        postService.rejectPost(eventId, postId, EventController.requireUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{postId}/like")
    public ResponseEntity<ApiResponse<PostResponse>> toggleLike(@PathVariable UUID eventId, @PathVariable UUID postId) {
        return ResponseEntity.ok(ApiResponse.ok(
                postService.toggleLike(eventId, postId, EventController.requireUserId())));
    }

    @PostMapping("/{postId}/report")
    public ResponseEntity<ApiResponse<PostReportResponse>> report(@PathVariable UUID eventId, @PathVariable UUID postId,
                                                                    @Valid @RequestBody CreateReportRequest req) {
        PostReportResponse report = postService.reportPost(eventId, postId, EventController.requireUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(report));
    }

    /** Moderators only — see EventPostService.listReportedPosts. */
    @GetMapping("/reported")
    public ResponseEntity<ApiResponse<List<PostReportResponse>>> listReported(@PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                postService.listReportedPosts(eventId, EventController.requireUserId(), page, size)));
    }

    @PostMapping("/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentResponse>> addComment(@PathVariable UUID eventId, @PathVariable UUID postId,
                                                                     @Valid @RequestBody CreateCommentRequest req) {
        CommentResponse comment = postService.addComment(eventId, postId, EventController.requireUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(comment));
    }

    @GetMapping("/{postId}/comments")
    public ResponseEntity<ApiResponse<List<CommentResponse>>> listComments(@PathVariable UUID eventId, @PathVariable UUID postId) {
        return ResponseEntity.ok(ApiResponse.ok(
                postService.listComments(eventId, postId, EventController.requireUserId())));
    }

    @DeleteMapping("/{postId}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable UUID eventId, @PathVariable UUID postId,
                                               @PathVariable UUID commentId) {
        postService.deleteComment(eventId, postId, commentId, EventController.requireUserId());
        return ResponseEntity.noContent().build();
    }
}
