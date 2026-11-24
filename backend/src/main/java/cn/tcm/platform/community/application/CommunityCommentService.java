package cn.tcm.platform.community.application;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.common.PageResult;
import cn.tcm.platform.community.infrastructure.CommunityCommentRepository;
import cn.tcm.platform.community.infrastructure.CommunityCommentRepository.Location;
import cn.tcm.platform.community.infrastructure.CommunityCommentRepository.LockedComment;
import cn.tcm.platform.community.infrastructure.CommunityCommentRepository.Viewer;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository.Target;
import cn.tcm.platform.community.infrastructure.CommunitySectionRepository;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.TreeSet;

import static cn.tcm.platform.community.api.CommunityDtos.*;
import static cn.tcm.platform.community.application.CommunitySectionService.POST;
import static cn.tcm.platform.community.application.CommunitySectionService.requireVersion;
import static cn.tcm.platform.content.ContentSupport.*;

@Service
public class CommunityCommentService {
    private final CommunityCommentRepository comments;
    private final CommunityPostRepository posts;
    private final CommunitySectionRepository sections;
    private final CommunitySectionService permissions;
    private final CommunityWriteGuard writes;
    private final CommunityTextValidator validator;
    private final ContentSupport sql;

    public CommunityCommentService(CommunityCommentRepository comments, CommunityPostRepository posts,
                                   CommunitySectionRepository sections, CommunitySectionService permissions,
                                   CommunityWriteGuard writes, CommunityTextValidator validator, ContentSupport sql) {
        this.comments = comments;
        this.posts = posts;
        this.sections = sections;
        this.permissions = permissions;
        this.writes = writes;
        this.validator = validator;
        this.sql = sql;
    }

    @Transactional(readOnly = true)
    public PageResult<CommentView> list(long actor, long resource, Long rootId, int page, int size) {
        permissions.requireActive(actor);
        int offset = PageResult.offset(page, size);
        Viewer viewer = publicViewer(actor, resource);
        if (rootId != null) {
            Location root = comments.locate(rootId);
            if (root.resourceId() != resource || root.rootId() != null || root.parentId() != null) throw ApiException.missing();
        }
        return comments.list(viewer, resource, rootId, page, size, offset);
    }

    public CommentView create(long actor, long resource, String key, CommentCreate input) {
        permissions.requireActive(actor);
        sql.require(actor, POST);
        String body = CommunityTextValidator.lf(input.body());
        Long rootId = input.rootCommentId() == null ? null : id(input.rootCommentId());
        Long parentId = input.replyToCommentId() == null ? null : id(input.replyToCommentId());
        if ((rootId == null) != (parentId == null)) throw ApiException.invalid("回复必须同时指定根评论及回复对象");
        Target target = posts.locatePublic(resource);
        Location root = rootId == null ? null : comments.locateReplyTarget(rootId);
        Location parent = parentId == null || parentId.equals(rootId) ? root : comments.locateReplyTarget(parentId);
        var participants = new TreeSet<Long>();
        participants.add(target.ownerId());
        if (root != null) {
            participants.add(root.authorId());
            participants.add(parent.authorId());
        }
        String hash = CommunitySourceHash.canonical(map("body", body, "rootCommentId", sid(rootId), "replyToCommentId", sid(parentId)));
        return writes.executeLocked(actor, participants, "COMMENT_CREATE:" + resource, key, hash, "COMMENT_CREATE",
                () -> sql.require(actor, POST), gate -> {
                    lockWritablePost(target);
                    if (root != null) lockAndValidateReply(resource, root, parent);
                    var policy = gate.policy();
                    String source = validator.comment(body, policy);
                    gate.charge();
                    long comment = comments.create(resource, actor, rootId, parentId);
                    long revision = comments.freeze(comment, 1, null, source, policy.id(), actor);
                    comments.publish(comment, revision, false);
                    comments.refreshReplyTime(resource);
                    sql.audit(actor, "COMMENT_PUBLISHED", "COMMENT", comment);
                    return new CommunityWriteGuard.Result("COMMENT", comment, 201);
                }, result -> comments.view(publicViewer(actor, resource), result.id()));
    }

    private void lockAndValidateReply(long resource, Location root, Location parent) {
        if (root.resourceId() != resource || parent.resourceId() != resource || root.rootId() != null || root.parentId() != null
                || (root.id() != parent.id() && !Long.valueOf(root.id()).equals(parent.rootId()))) {
            throw ApiException.invalid("回复对象必须属于同一帖子和评论串");
        }
        LockedComment lockedParent;
        if (root.id() == parent.id()) {
            lockedParent = comments.lock(root);
        } else if (root.id() < parent.id()) {
            comments.lock(root);
            lockedParent = comments.lock(parent);
        } else {
            lockedParent = comments.lock(parent);
            comments.lock(root);
        }
        if (!comments.isPublic(lockedParent)) throw ApiException.conflict("回复对象当前不可回复");
    }

    public CommentView revise(long actor, long id, String key, CommentEdit input) {
        long base = id(input.baseRevisionId());
        String body = CommunityTextValidator.lf(input.body());
        Location located = comments.locate(id);
        Target target = posts.locate(located.resourceId());
        String hash = CommunitySourceHash.canonical(map("baseRevisionId", Long.toString(base), "lockVersion", input.lockVersion(), "body", body));
        return writes.executeLocked(actor, List.of(target.ownerId()), "COMMENT_REVISE:" + id, key, hash, "PUBLIC_EDIT",
                () -> {
                    requireAuthor(actor, located);
                    sql.require(actor, POST);
                }, gate -> {
                    lockWritablePost(target);
                    LockedComment comment = comments.lock(located);
                    if (!comments.isPublic(comment)) throw ApiException.conflict("当前评论不能编辑");
                    requireVersion(comment.lockVersion(), input.lockVersion());
                    if (comment.publishedRevisionId() != base) throw ApiException.conflict("修订基于过期版本，请刷新后重试");
                    var policy = gate.policy();
                    String source = validator.comment(body, policy);
                    int version = comments.nextVersion(id);
                    gate.charge();
                    long revision = comments.freeze(id, version, base, source, policy.id(), actor);
                    comments.publish(id, revision, true);
                    sql.audit(actor, "COMMENT_REVISED", "COMMENT", id);
                    return new CommunityWriteGuard.Result("COMMENT_REVISION", revision, 201);
                }, result -> comments.writtenRevision(publicViewer(actor, located.resourceId()), id, result.id()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommentView delete(long actor, long id, int version) {
        if (version < 0) throw ApiException.invalid("锁版本无效");
        Location located = comments.locate(id);
        permissions.lockActive(actor);
        requireAuthor(actor, located);
        sql.require(actor, POST);
        Target target = posts.locate(located.resourceId());
        sections.lockHierarchy(target.sectionId());
        posts.lock(target);
        LockedComment comment = comments.lock(located);
        if (!"DELETED".equals(comment.status())) {
            requireVersion(comment.lockVersion(), version);
            comments.delete(id);
            comments.refreshReplyTime(located.resourceId());
            sql.audit(actor, "COMMENT_DELETED", "COMMENT", id);
        }
        return comments.view(new Viewer(actor, true, false), id);
    }

    private static void requireAuthor(long actor, Location comment) {
        if (actor != comment.authorId()) throw ApiException.missing();
    }

    private void lockWritablePost(Target target) {
        var hierarchy = sections.lockHierarchy(target.sectionId());
        var post = posts.lock(target);
        if (!posts.isPublic(post)) throw ApiException.missing();
        hierarchy.requireWritable();
        if (!post.commentsEnabled()) throw ApiException.conflict("当前帖子已关闭评论");
    }

    private Viewer publicViewer(long actor, long resource) {
        return new Viewer(actor, sql.has(actor, POST), posts.commentsWritable(resource));
    }
}
