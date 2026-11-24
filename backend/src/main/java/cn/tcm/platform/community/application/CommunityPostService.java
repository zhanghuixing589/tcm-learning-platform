package cn.tcm.platform.community.application;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.common.PageResult;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository.Grantee;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository.LockedPost;
import cn.tcm.platform.community.infrastructure.CommunitySectionRepository;
import cn.tcm.platform.content.ContentSupport;
import cn.tcm.platform.content.application.PublishedResourceAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.tcm.platform.community.api.CommunityDtos.*;
import static cn.tcm.platform.community.application.CommunitySectionService.*;
import static cn.tcm.platform.community.infrastructure.CommunityPostRepository.*;
import static cn.tcm.platform.content.ContentSupport.*;

@Service
public class CommunityPostService {
    private final CommunityPostRepository posts;
    private final CommunitySectionRepository sections;
    private final CommunitySectionService permissions;
    private final PublishedResourceAccess publications;
    private final CommunityWriteGuard writes;
    private final CommunityTextValidator validator;
    private final ContentSupport sql;

    public CommunityPostService(CommunityPostRepository posts, CommunitySectionRepository sections, CommunitySectionService permissions,
                                PublishedResourceAccess publications, CommunityWriteGuard writes, CommunityTextValidator validator,
                                ContentSupport sql) {
        this.posts = posts;
        this.sections = sections;
        this.permissions = permissions;
        this.publications = publications;
        this.writes = writes;
        this.validator = validator;
        this.sql = sql;
    }

    @Transactional(readOnly = true)
    public PageResult<PostView> list(long actor, Long section, boolean children, String type, String q,
                                     String sort, int page, int size) {
        permissions.requireActive(actor);
        int offset = PageResult.offset(page, size);
        if (!Set.of("ALL", "POST", "ARTICLE").contains(type)) throw ApiException.invalid("帖子类型无效");
        if (!Set.of("NEWEST", "LATEST_REPLY").contains(sort)) throw ApiException.invalid("排序方式无效");
        if (section != null) sections.find(section);
        String term = q == null ? "" : q.strip();
        if (CommunityTextValidator.chars(term) > 100) throw ApiException.invalid("搜索词最多100字符");
        return posts.list(grantee(actor), section, children, type, term, "LATEST_REPLY".equals(sort), page, size, offset);
    }

    @Transactional
    public PostView detail(long actor, long id) {
        permissions.requireActive(actor);
        PostView view = requirePublic(actor, id);
        publications.recordView(actor, id, view.resourceType());
        return view;
    }

    public PostView create(long actor, String key, PostCreate input) {
        long section = id(input.sectionId());
        String requestedBody = CommunityTextValidator.lf(input.body());
        String format = input.sourceFormat() == null ? "MARKDOWN" : input.sourceFormat();
        String hash = CommunitySourceHash.canonical(map("sectionId", Long.toString(section), "title", input.title(),
                "body", requestedBody, "sourceFormat", format));
        return writes.executeLocked(actor, List.of(), "POST_CREATE", key, hash, "POST_CREATE",
                () -> sql.require(actor, POST), gate -> {
                    sections.lockHierarchy(section).requireWritable();
                    var policy = gate.policy();
                    String title = validator.title(input.title(), null, policy);
                    String body = validator.body(requestedBody, format, policy.limit("maxPostChars"), policy);
                    gate.charge();
                    long resource = sql.insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('POST',?)", actor);
                    sql.db.update("INSERT INTO community_post_profiles(resource_id,section_id) VALUES(?,?)", resource, section);
                    long revision = freeze(actor, resource, 1, null, section, title, body, format, policy.id(), null);
                    sql.db.update("""
                            UPDATE content_resources SET visibility_status='PUBLISHED',published_revision_id=?,published_at=UTC_TIMESTAMP(3)
                            WHERE id=?
                            """, revision, resource);
                    sql.audit(actor, "POST_PUBLISHED", "POST", resource);
                    return new CommunityWriteGuard.Result("POST", resource, 201);
                }, result -> {
                    posts.own(actor, result.id());
                    return requirePublic(actor, result.id());
                });
    }

    public PostView revise(long actor, long resource, String key, PostEdit input) {
        long base = id(input.baseRevisionId());
        String requestedBody = CommunityTextValidator.lf(input.body());
        String hash = CommunitySourceHash.canonical(map("baseRevisionId", Long.toString(base), "lockVersion", input.lockVersion(),
                "title", input.title(), "body", requestedBody, "changeNote", input.changeNote()));
        return writes.executeLocked(actor, List.of(), "POST_REVISE:" + resource, key, hash, "PUBLIC_EDIT",
                () -> {
                    posts.own(actor, resource);
                    sql.require(actor, POST);
                }, gate -> {
                    Target target = posts.locate(resource);
                    sections.lockHierarchy(target.sectionId()).requireWritable();
                    LockedPost post = posts.lock(target);
                    if (post.target().ownerId() != actor || !"POST".equals(post.type())) throw ApiException.missing();
                    if (!posts.isPublic(post)) throw ApiException.conflict("当前状态不能重新公开讨论");
                    requireVersion(post.lockVersion(), input.lockVersion());
                    Map<String, Object> latest = posts.latestLocked(resource);
                    if (number(latest.get("id")) != base || post.publishedRevisionId() != base)
                        throw ApiException.conflict("修订基于过期版本，请刷新后重试");
                    var policy = gate.policy();
                    String format = format((String) latest.get("source_format"));
                    String title = validator.title(input.title(), (String) latest.get("title"), policy);
                    boolean unchanged = requestedBody.equals(CommunityTextValidator.lf((String) latest.get("body")));
                    String body = unchanged ? requestedBody : validator.body(requestedBody, format, policy.limit("maxPostChars"), policy);
                    Long priorPolicy = nullableNumber(latest.get("format_policy_id"));
                    long policyId = unchanged && priorPolicy != null ? priorPolicy : policy.id();
                    String note = field(input.changeNote(), 1000);
                    gate.charge();
                    long revision = freeze(actor, resource, intValue(latest.get("version_no")) + 1, base, target.sectionId(),
                            title, body, format, policyId, note);
                    sql.db.update("UPDATE content_resources SET published_revision_id=?,lock_version=lock_version+1 WHERE id=?", revision, resource);
                    sql.db.update("UPDATE community_post_profiles SET public_edited_at=UTC_TIMESTAMP(3) WHERE resource_id=?", resource);
                    sql.audit(actor, "POST_REVISED", "POST", resource);
                    return new CommunityWriteGuard.Result("POST_REVISION", revision, 201);
                }, result -> posts.writtenRevision(grantee(actor), resource, result.id()));
    }

    private long freeze(long actor, long resource, int version, Long base, long section, String title, String body,
                        String format, long policy, String note) {
        long revision = sql.insert("""
                INSERT INTO content_revisions(resource_id,version_no,base_revision_id,title,body,revision_state,change_note,created_by,approved_at)
                VALUES(?,?,?,?,?,'APPROVED',?,?,UTC_TIMESTAMP(3))
                """, resource, version, base, title, body, note, actor);
        sql.db.update("""
                INSERT INTO community_revision_profiles(revision_id,resource_id,section_id,source_format,format_policy_id,release_method,source_sha256,body_char_count)
                VALUES(?,?,?,?,?,'DIRECT',?,?)
                """, revision, resource, section, format, policy,
                CommunitySourceHash.content(title, body, Long.toString(section), format, List.of()), CommunityTextValidator.chars(body));
        return revision;
    }

    @Transactional(readOnly = true)
    public OwnPost own(long actor, long resource) {
        permissions.requireActive(actor);
        var row = posts.own(actor, resource);
        var latest = posts.source(posts.latest(resource), false);
        PostView published = posts.published(grantee(actor), resource);
        var capabilities = published == null ? new PostCapabilities(false, false, false) : published.capabilities();
        if (!latest.revisionId().equals(sid(row.get("published_revision_id")))) {
            capabilities = new PostCapabilities(false, capabilities.canComment(), capabilities.canPin());
        }
        return new OwnPost(Long.toString(resource), "POST", (String) row.get("visibility_status"),
                sid(row.get("published_revision_id")), published, latest, intValue(row.get("lock_version")), capabilities);
    }

    @Transactional(readOnly = true)
    public PageResult<RevisionHeader> revisions(long actor, long resource, int page, int size) {
        permissions.requireActive(actor);
        posts.own(actor, resource);
        return posts.revisions(resource, page, size, PageResult.offset(page, size));
    }

    @Transactional(readOnly = true)
    public RevisionDiff diff(long actor, long from, long to) {
        permissions.requireActive(actor);
        var before = posts.ownRevision(actor, from);
        var after = from == to ? before : posts.ownRevision(actor, to);
        long resource = number(before.get("resource_id"));
        if (resource != number(after.get("resource_id"))) throw ApiException.missing();
        RevisionSource left = posts.source(before, true);
        RevisionSource right = posts.source(after, true);
        return new RevisionDiff(Long.toString(resource), left, right, change(left.title(), right.title()), change(left.body(), right.body()));
    }

    // A single changed span bounded by the common prefix and suffix, in Unicode code points.
    private static SourceChange change(String before, String after) {
        int start = 0;
        int prefix = 0;
        while (start < before.length() && start < after.length()) {
            int point = before.codePointAt(start);
            if (point != after.codePointAt(start)) break;
            start += Character.charCount(point);
            prefix++;
        }
        int left = before.length();
        int right = after.length();
        int suffix = 0;
        while (left > start && right > start) {
            int point = before.codePointBefore(left);
            if (point != after.codePointBefore(right)) break;
            int width = Character.charCount(point);
            left -= width;
            right -= width;
            suffix++;
        }
        return new SourceChange(prefix, suffix, before.codePointCount(start, left), after.codePointCount(start, right));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PinView pin(long actor, long sectionId, long resource, PinInput input) {
        permissions.requireActive(actor);
        Target located = posts.locatePublic(resource);
        if (located.sectionId() != sectionId) throw ApiException.missing();
        lockAccounts(actor, located.ownerId());
        var hierarchy = sections.lockHierarchy(sectionId);
        if (!hierarchy.section().own(actor)) sql.require(actor, MODERATE);
        hierarchy.requireWritable();
        LockedPost post = posts.lock(located);
        if (!posts.isPublic(post)) throw ApiException.missing();
        int version = post.pinLockVersion();
        requireVersion(version, input.lockVersion());
        if (post.pinned() != input.pinned()) {
            sql.db.update("UPDATE community_post_profiles SET is_pinned=?,lock_version=lock_version+1 WHERE resource_id=?", input.pinned(), resource);
            sections.event(sectionId, null, actor, "POST_PIN_CHANGED", null,
                    Map.of("resourceId", Long.toString(resource), "pinned", post.pinned()),
                    Map.of("resourceId", Long.toString(resource), "pinned", input.pinned()));
            version++;
        }
        return new PinView(Long.toString(resource), Long.toString(sectionId), input.pinned(), version);
    }

    private void lockAccounts(long actor, long owner) {
        sql.db.queryForList("SELECT id FROM sys_users WHERE id IN (?,?) ORDER BY id FOR UPDATE", Math.min(actor, owner), Math.max(actor, owner));
        permissions.requireActive(actor);
    }

    private Grantee grantee(long actor) { return new Grantee(actor, sql.has(actor, POST), sql.has(actor, MODERATE)); }

    private PostView requirePublic(long actor, long resource) {
        PostView view = posts.published(grantee(actor), resource);
        if (view == null) throw ApiException.missing();
        return view;
    }
}
