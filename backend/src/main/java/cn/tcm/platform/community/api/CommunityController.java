package cn.tcm.platform.community.api;

import cn.tcm.platform.common.ApiResponse;
import cn.tcm.platform.community.application.CommunityCommentService;
import cn.tcm.platform.community.application.CommunityPostService;
import cn.tcm.platform.community.application.CommunitySectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static cn.tcm.platform.community.api.CommunityDtos.*;
import static cn.tcm.platform.content.ContentSupport.id;

@RestController
@RequestMapping("/api/v1")
public class CommunityController {
    private final CommunitySectionService sections;
    private final CommunityPostService posts;
    private final CommunityCommentService comments;

    public CommunityController(CommunitySectionService sections, CommunityPostService posts, CommunityCommentService comments) {
        this.sections = sections;
        this.posts = posts;
        this.comments = comments;
    }

    @GetMapping("/community/sections")
    public ApiResponse<?> sections(Principal actor, @RequestParam(required = false) String parentId,
                                  @RequestParam(defaultValue = "false") boolean joinedOnly) {
        return ApiResponse.ok(sections.list(actor(actor), optionalId(parentId), joinedOnly));
    }

    @GetMapping("/community/sections/{sectionId}")
    public ApiResponse<?> section(Principal actor, @PathVariable String sectionId) {
        return ApiResponse.ok(sections.detail(actor(actor), id(sectionId)));
    }

    @PutMapping("/community/sections/{sectionId}/membership")
    public ApiResponse<?> join(Principal actor, @PathVariable String sectionId) {
        return ApiResponse.ok(sections.membership(actor(actor), id(sectionId), true));
    }

    @DeleteMapping("/community/sections/{sectionId}/membership")
    public ApiResponse<?> leave(Principal actor, @PathVariable String sectionId) {
        return ApiResponse.ok(sections.membership(actor(actor), id(sectionId), false));
    }

    @PatchMapping("/community/sections/{sectionId}/profile")
    public ApiResponse<?> profile(Principal actor, @PathVariable String sectionId, @Valid @RequestBody SectionProfile input) {
        return ApiResponse.ok(sections.profile(actor(actor), id(sectionId), input));
    }

    @PutMapping("/community/sections/{sectionId}/posts/{resourceId}/pin")
    public ApiResponse<?> pin(Principal actor, @PathVariable String sectionId, @PathVariable String resourceId,
                              @Valid @RequestBody PinInput input) {
        return ApiResponse.ok(posts.pin(actor(actor), id(sectionId), id(resourceId), input));
    }

    @GetMapping("/posts")
    public ApiResponse<?> posts(Principal actor, @RequestParam(required = false) String sectionId,
                               @RequestParam(defaultValue = "true") boolean includeChildren,
                               @RequestParam(defaultValue = "ALL") String type, @RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "NEWEST") String sort,
                               @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(posts.list(actor(actor), optionalId(sectionId), includeChildren, type, q, sort, page, pageSize));
    }

    @GetMapping("/posts/{resourceId}")
    public ApiResponse<?> post(Principal actor, @PathVariable String resourceId) {
        return ApiResponse.ok(posts.detail(actor(actor), id(resourceId)));
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createPost(Principal actor, @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody PostCreate input) {
        return ApiResponse.ok(posts.create(actor(actor), key, input));
    }

    @PostMapping("/posts/{resourceId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> revisePost(Principal actor, @PathVariable String resourceId,
                                    @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PostEdit input) {
        return ApiResponse.ok(posts.revise(actor(actor), id(resourceId), key, input));
    }

    @GetMapping("/me/posts/{resourceId}")
    public ApiResponse<?> ownPost(Principal actor, @PathVariable String resourceId) {
        return ApiResponse.ok(posts.own(actor(actor), id(resourceId)));
    }

    @GetMapping("/me/posts/{resourceId}/revisions")
    public ApiResponse<?> revisions(Principal actor, @PathVariable String resourceId,
                                   @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(posts.revisions(actor(actor), id(resourceId), page, pageSize));
    }

    @GetMapping("/me/community-revisions/diff")
    public ApiResponse<?> diff(Principal actor, @RequestParam String fromRevisionId, @RequestParam String toRevisionId) {
        return ApiResponse.ok(posts.diff(actor(actor), id(fromRevisionId), id(toRevisionId)));
    }

    @GetMapping("/posts/{resourceId}/comments")
    public ApiResponse<?> comments(Principal actor, @PathVariable String resourceId, @RequestParam(required = false) String rootId,
                                  @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(comments.list(actor(actor), id(resourceId), optionalId(rootId), page, pageSize));
    }

    @PostMapping("/posts/{resourceId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createComment(Principal actor, @PathVariable String resourceId,
                                       @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CommentCreate input) {
        return ApiResponse.ok(comments.create(actor(actor), id(resourceId), key, input));
    }

    @PostMapping("/comments/{commentId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> editComment(Principal actor, @PathVariable String commentId,
                                     @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CommentEdit input) {
        return ApiResponse.ok(comments.revise(actor(actor), id(commentId), key, input));
    }

    @DeleteMapping("/comments/{commentId}")
    public ApiResponse<?> deleteComment(Principal actor, @PathVariable String commentId, @RequestParam int lockVersion) {
        return ApiResponse.ok(comments.delete(actor(actor), id(commentId), lockVersion));
    }

    static long actor(Principal principal) { return id(principal.getName()); }
    static Long optionalId(String value) { return value == null ? null : id(value); }
}
