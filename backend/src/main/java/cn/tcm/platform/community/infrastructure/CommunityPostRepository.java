package cn.tcm.platform.community.infrastructure;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.common.PageResult;
import cn.tcm.platform.community.application.CommunityReference;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.tcm.platform.community.api.CommunityDtos.*;
import static cn.tcm.platform.content.ContentSupport.*;
import static cn.tcm.platform.content.application.PublishedResourceAccess.*;

@Repository
public class CommunityPostRepository {
    public static final String TYPES = "r.resource_type IN ('POST','ARTICLE')";
    private static final String PROFILE_JOINS = JOINS + """
             JOIN community_post_profiles cp ON cp.resource_id=r.id
             JOIN community_sections s ON s.id=cp.section_id
             LEFT JOIN community_sections par ON par.id=s.parent_id
            """;
    private static final String PUBLISHED_PROFILE = """
             LEFT JOIN community_revision_profiles rp ON rp.revision_id=p.id AND rp.resource_id=r.id
            """;
    private static final String WRITABLE = """
            (s.active=1 AND s.lifecycle<>'ARCHIVED' AND s.code<>'legacy-content'
             AND (s.parent_id IS NULL OR (par.active=1 AND par.lifecycle<>'ARCHIVED' AND par.code<>'legacy-content')))
            """;
    private static final String COMMENT_FROM = """
            FROM community_comments c JOIN sys_users cu ON cu.id=c.user_id AND cu.status='ACTIVE'
            JOIN community_comment_revisions cv ON cv.comment_id=c.id AND cv.id=c.published_revision_id AND cv.revision_state='APPROVED'
            WHERE c.resource_id=r.id AND c.status='VISIBLE'
            """;
    private static final String LAST_REPLY = "(SELECT MAX(c.created_at) " + COMMENT_FROM + ")";
    private static final String PUBLIC_SELECT = select("p", "cp.public_edited_at");
    private static final String REVISION_FIELDS = """
            SELECT v.*,rp.section_id,rp.source_format,rp.format_policy_id,rp.release_method,rp.source_sha256,rp.body_char_count
            FROM content_revisions v LEFT JOIN community_revision_profiles rp ON rp.revision_id=v.id AND rp.resource_id=v.resource_id
            """;

    private final ContentSupport sql;

    public CommunityPostRepository(ContentSupport sql) { this.sql = sql; }

    public record Grantee(long id, boolean poster, boolean moderator) {}
    public record Target(long id, long ownerId, long sectionId) {}
    public record LockedPost(Target target, String type, String status, Long publishedRevisionId, int lockVersion,
                             boolean pinned, int pinLockVersion, boolean commentsEnabled) {}

    private static String select(String revisionAlias, String editedAt) {
        return """
                SELECT r.id,r.resource_type,r.owner_id,r.published_at,%1$s.id revision_id,%1$s.title,
                       LEFT(%1$s.body,180) summary,o.nickname,o.avatar_path,cp.comments_enabled,cp.is_pinned,cp.lock_version pin_lock,
                       %2$s public_edited_at,s.id section_id,s.parent_id,s.name section_name,s.code section_code,s.owner_id section_owner,
                       rp.source_format,rp.format_policy_id,rp.release_method,
                       EXISTS(SELECT 1 FROM resource_likes a WHERE a.resource_id=r.id AND a.user_id=?) liked,
                       EXISTS(SELECT 1 FROM resource_bookmarks b WHERE b.resource_id=r.id AND b.user_id=?) bookmarked,
                """.formatted(revisionAlias, editedAt) + LIKES + " AS like_count," + BOOKMARKS + " AS bookmark_count,"
                + WRITABLE + " AS writable," + LAST_REPLY + " AS last_reply,"
                + "(SELECT COUNT(*) " + COMMENT_FROM + ") AS comment_count";
    }

    public Target locate(long id) {
        Map<String, Object> row = sql.row("""
                SELECT r.id,r.owner_id,cp.section_id FROM content_resources r
                JOIN community_post_profiles cp ON cp.resource_id=r.id
                WHERE r.id=? AND
                """ + TYPES, id);
        return new Target(id, number(row.get("owner_id")), number(row.get("section_id")));
    }

    public Target locatePublic(long id) {
        var row = sql.row("SELECT r.owner_id,cp.section_id" + PROFILE_JOINS
                + " WHERE r.id=? AND " + TYPES + " AND " + AVAILABLE, id);
        return new Target(id, number(row.get("owner_id")), number(row.get("section_id")));
    }

    public LockedPost lock(Target located) {
        var r = sql.row("SELECT * FROM content_resources WHERE id=? FOR UPDATE", located.id());
        var cp = sql.row("SELECT * FROM community_post_profiles WHERE resource_id=? FOR UPDATE", located.id());
        if (number(r.get("owner_id")) != located.ownerId() || number(cp.get("section_id")) != located.sectionId()) {
            throw ApiException.conflict("帖子归属已变化，请刷新");
        }
        String type = (String) r.get("resource_type");
        if (!"POST".equals(type) && !"ARTICLE".equals(type)) throw ApiException.missing();
        return new LockedPost(located, type, (String) r.get("visibility_status"),
                nullableNumber(r.get("published_revision_id")), intValue(r.get("lock_version")),
                bool(cp.get("is_pinned")), intValue(cp.get("lock_version")), bool(cp.get("comments_enabled")));
    }

    public boolean isPublic(LockedPost post) {
        if (!"PUBLISHED".equals(post.status()) || post.publishedRevisionId() == null) return false;
        var revisions = sql.db.queryForList("SELECT revision_state FROM content_revisions WHERE resource_id=? AND id=? FOR UPDATE",
                String.class, post.target().id(), post.publishedRevisionId());
        return !revisions.isEmpty() && "APPROVED".equals(revisions.get(0))
                && sql.count("SELECT COUNT(*) FROM sys_users WHERE id=? AND status='ACTIVE'", post.target().ownerId()) == 1;
    }

    public boolean commentsWritable(long resource) {
        var rows = sql.db.queryForList("SELECT (cp.comments_enabled=1 AND " + WRITABLE + ")" + PROFILE_JOINS
                + " WHERE r.id=? AND " + TYPES + " AND " + AVAILABLE, Boolean.class, resource);
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.get(0);
    }

    public PageResult<PostView> list(Grantee viewer, Long sectionId, boolean includeChildren, String type, String term,
                                     boolean replyOrder, int page, int size, int offset) {
        String where = " WHERE " + AVAILABLE + " AND " + TYPES;
        List<Object> filters = new ArrayList<>();
        if (sectionId != null) {
            where += includeChildren ? " AND (s.id=? OR s.parent_id=?)" : " AND s.id=?";
            filters.add(sectionId);
            if (includeChildren) filters.add(sectionId);
        }
        if (!"ALL".equals(type)) {
            where += " AND r.resource_type=?";
            filters.add(type);
        }
        if (!term.isBlank()) {
            where += " AND (p.title LIKE ? ESCAPE '!' OR p.body LIKE ? ESCAPE '!')";
            String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            filters.add(pattern);
            filters.add(pattern);
        }
        long total = sql.count("SELECT COUNT(*)" + PROFILE_JOINS + where, filters.toArray());
        List<Object> args = new ArrayList<>();
        args.add(viewer.id());
        args.add(viewer.id());
        args.addAll(filters);
        args.add(size);
        args.add(offset);
        String order = " ORDER BY cp.is_pinned DESC," + (replyOrder ? "last_reply DESC," : "") + "r.published_at DESC,r.id DESC";
        List<PostView> items = sql.db.query(PUBLIC_SELECT + ",NULL body" + PROFILE_JOINS + PUBLISHED_PROFILE + where + order + " LIMIT ? OFFSET ?",
                (rs, n) -> publicView(rs, viewer, List.of()), args.toArray());
        return new PageResult<>(page, size, total, items);
    }

    public PostView published(Grantee viewer, long id) {
        return published(viewer, id, true);
    }

    public PostView published(Grantee viewer, long id, boolean includeBody) {
        String body = includeBody ? ",p.body" : ",NULL body";
        var rows = sql.db.query(PUBLIC_SELECT + body + PROFILE_JOINS + PUBLISHED_PROFILE + " WHERE r.id=? AND " + TYPES + " AND " + AVAILABLE,
                (rs, n) -> publicView(rs, viewer, "ARTICLE".equals(rs.getString("resource_type"))
                        ? references(rs.getLong("revision_id")) : List.of()), viewer.id(), viewer.id(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Owner-only receipt result, gated by the resource's current publication pointer and author state. */
    public PostView writtenRevision(Grantee viewer, long resource, long revision) {
        String joins = PROFILE_JOINS + """
                 JOIN content_revisions w ON w.resource_id=r.id AND w.id=? AND w.created_by=r.owner_id AND w.revision_state='APPROVED'
                 JOIN community_revision_profiles rp ON rp.revision_id=w.id AND rp.resource_id=r.id AND rp.release_method='DIRECT'
                 WHERE r.id=? AND r.owner_id=? AND r.resource_type='POST' AND
                """ + AVAILABLE;
        var rows = sql.db.query(select("w", "w.approved_at") + ",w.body" + joins,
                (rs, n) -> publicView(rs, viewer, List.of()), viewer.id(), viewer.id(), revision, resource, viewer.id());
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.get(0);
    }

    private static PostView publicView(ResultSet rs, Grantee viewer, List<CommunityReference> references) throws SQLException {
        long owner = rs.getLong("owner_id");
        boolean owns = owner == viewer.id();
        boolean sectionOwner = Long.valueOf(viewer.id()).equals(rs.getObject("section_owner", Long.class));
        String type = rs.getString("resource_type");
        boolean writable = rs.getBoolean("writable");
        boolean comments = rs.getBoolean("comments_enabled");
        var capabilities = new PostCapabilities("POST".equals(type) && owns && viewer.poster() && writable,
                comments && writable && viewer.poster(), writable && (sectionOwner || viewer.moderator()));
        var section = new SectionRef(rs.getString("section_id"), rs.getString("parent_id"), rs.getString("section_code"), rs.getString("section_name"));
        var author = person(rs.getString("owner_id"), rs.getString("nickname"), rs.getString("avatar_path"));
        return new PostView(rs.getString("id"), type, section, rs.getString("revision_id"), rs.getString("title"),
                rs.getString("body"), rs.getString("summary"), format(rs.getString("source_format")),
                rs.getString("format_policy_id"), release(rs.getString("release_method")), references, author,
                time(rs, "published_at"), time(rs, "public_edited_at"), time(rs, "last_reply"), rs.getLong("comment_count"),
                rs.getLong("like_count"), rs.getLong("bookmark_count"), rs.getBoolean("liked"), rs.getBoolean("bookmarked"),
                comments, rs.getBoolean("is_pinned"), rs.getInt("pin_lock"), capabilities);
    }

    public Map<String, Object> own(long owner, long resource) {
        return sql.row("SELECT r.* FROM content_resources r WHERE r.id=? AND r.owner_id=? AND r.resource_type='POST'", resource, owner);
    }

    public Map<String, Object> latest(long resource) {
        return sql.row(REVISION_FIELDS + " WHERE v.resource_id=? ORDER BY v.version_no DESC LIMIT 1", resource);
    }

    public Map<String, Object> latestLocked(long resource) {
        // Join only after locating the current version under the resource lock.
        long id = number(sql.row("SELECT id FROM content_revisions WHERE resource_id=? ORDER BY version_no DESC LIMIT 1 FOR UPDATE", resource).get("id"));
        return sql.row(REVISION_FIELDS + " WHERE v.resource_id=? AND v.id=? FOR UPDATE", resource, id);
    }

    public PageResult<RevisionHeader> revisions(long resource, int page, int size, int offset) {
        long total = sql.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?", resource);
        List<RevisionHeader> items = sql.db.query("""
                SELECT v.id,v.version_no,v.base_revision_id,v.revision_state,v.title,v.change_note,v.created_at,rp.release_method
                FROM content_revisions v LEFT JOIN community_revision_profiles rp ON rp.revision_id=v.id AND rp.resource_id=v.resource_id
                WHERE v.resource_id=? ORDER BY v.version_no DESC LIMIT ? OFFSET ?
                """, (rs, n) -> new RevisionHeader(rs.getString("id"), rs.getInt("version_no"),
                rs.getString("base_revision_id"), rs.getString("revision_state"), rs.getString("title"),
                rs.getString("change_note"), release(rs.getString("release_method")), time(rs, "created_at")), resource, size, offset);
        return new PageResult<>(page, size, total, items);
    }

    public Map<String, Object> revision(long resource, long revision) {
        return sql.row(REVISION_FIELDS + " WHERE v.resource_id=? AND v.id=?", resource, revision);
    }

    public Map<String, Object> ownRevision(long owner, long revision) {
        return sql.row(REVISION_FIELDS + """
                JOIN content_resources r ON r.id=v.resource_id WHERE v.id=? AND r.owner_id=? AND
                """ + TYPES, revision, owner);
    }

    public RevisionSource source(Map<String, Object> row, boolean article) {
        long revision = number(row.get("id"));
        String body = (String) row.get("body");
        return new RevisionSource(Long.toString(revision), intValue(row.get("version_no")), (String) row.get("revision_state"),
                (String) row.get("title"), body, sid(row.get("section_id")), format((String) row.get("source_format")),
                sid(row.get("format_policy_id")), release((String) row.get("release_method")), (String) row.get("source_sha256"),
                row.get("body_char_count") == null ? body.codePointCount(0, body.length()) : intValue(row.get("body_char_count")),
                article ? references(revision) : List.of());
    }

    public List<CommunityReference> references(long revision) {
        return sql.db.query("""
                SELECT reference_key,display_number,title,authors,publication_year,source_name,doi,url,location_label
                FROM community_revision_references WHERE revision_id=? ORDER BY display_number
                """, (rs, n) -> new CommunityReference(rs.getString("reference_key"), rs.getInt("display_number"),
                rs.getString("title"), rs.getString("authors"), rs.getObject("publication_year", Integer.class),
                rs.getString("source_name"), rs.getString("doi"), rs.getString("url"), rs.getString("location_label")), revision);
    }

    public static Person person(String id, String nickname, String avatarPath) {
        return new Person(id, nickname, avatarPath == null ? null : "/api/v1/users/" + id + "/avatar");
    }

    public static String time(ResultSet rs, String column) throws SQLException {
        return utc(rs.getObject(column, java.time.LocalDateTime.class));
    }

    public static Long nullableNumber(Object value) { return value == null ? null : number(value); }
    public static int intValue(Object value) { return ((Number) value).intValue(); }
    public static boolean bool(Object value) { return value instanceof Boolean b ? b : intValue(value) != 0; }
    public static String format(String sourceFormat) { return sourceFormat == null ? "PLAIN_TEXT" : sourceFormat; }
    private static String release(String releaseMethod) { return releaseMethod == null ? "LEGACY" : releaseMethod; }
}
