package cn.tcm.platform.community.infrastructure;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.common.PageResult;
import cn.tcm.platform.community.application.CommunitySourceHash;
import cn.tcm.platform.community.application.CommunityTextValidator;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static cn.tcm.platform.community.api.CommunityDtos.*;
import static cn.tcm.platform.community.infrastructure.CommunityPostRepository.*;

@Repository
public class CommunityCommentRepository {
    private static final String VISIBLE = "(c.status='VISIBLE' AND u.status='ACTIVE' AND v.revision_state='APPROVED')";
    private static final String JOIN = """
            FROM community_comments c JOIN sys_users u ON u.id=c.user_id
            LEFT JOIN community_comment_revisions v ON v.id=c.published_revision_id AND v.comment_id=c.id
            """;
    private static final String SELECT = select("v");
    private final ContentSupport sql;

    public CommunityCommentRepository(ContentSupport sql) { this.sql = sql; }

    public record Location(long id, long resourceId, long authorId, Long rootId, Long parentId) {}
    public record LockedComment(Location location, String status, Long publishedRevisionId, int lockVersion) {}
    public record Viewer(long id, boolean poster, boolean writable) {}

    private static String select(String revisionAlias) {
        return """
                SELECT c.id,c.resource_id,c.user_id,c.parent_comment_id,c.root_comment_id,c.lock_version,
                    c.created_at,u.nickname,u.avatar_path,%1$s.id revision_id,%1$s.body,%1$s.format_policy_id,
                    CASE WHEN %1$s.version_no>1 THEN %1$s.created_at END edited_at,
                    CASE WHEN c.root_comment_id IS NULL THEN
                        (SELECT COUNT(*) FROM community_comments replies WHERE replies.resource_id=c.resource_id AND replies.root_comment_id=c.id)
                        ELSE 0 END reply_count,
                    CASE WHEN c.status='DELETED' THEN 'DELETED' WHEN
                """.formatted(revisionAlias) + VISIBLE + " THEN 'VISIBLE' ELSE 'HIDDEN' END display_state ";
    }

    private static Location location(ResultSet rs) throws SQLException {
        return new Location(rs.getLong("id"), rs.getLong("resource_id"), rs.getLong("user_id"),
                rs.getObject("root_comment_id", Long.class), rs.getObject("parent_comment_id", Long.class));
    }

    public Location locate(long id) {
        var rows = sql.db.query("SELECT * FROM community_comments WHERE id=?", (rs, n) -> location(rs), id);
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.get(0);
    }

    public Location locateReplyTarget(long id) {
        try {
            return locate(id);
        } catch (ApiException ex) {
            throw ApiException.invalid("回复关系无效");
        }
    }

    public LockedComment lock(Location located) {
        var rows = sql.db.query("SELECT * FROM community_comments WHERE id=? FOR UPDATE",
                (rs, n) -> new LockedComment(location(rs), rs.getString("status"),
                        rs.getObject("published_revision_id", Long.class), rs.getInt("lock_version")), located.id());
        if (rows.isEmpty()) throw ApiException.missing();
        LockedComment row = rows.get(0);
        if (!located.equals(row.location())) throw ApiException.conflict("评论归属已变化，请刷新");
        return row;
    }

    public boolean isPublic(LockedComment comment) {
        if (!"VISIBLE".equals(comment.status()) || comment.publishedRevisionId() == null) return false;
        return sql.count("""
                SELECT COUNT(*) FROM community_comment_revisions v JOIN sys_users u ON u.id=?
                WHERE v.comment_id=? AND v.id=? AND v.revision_state='APPROVED' AND u.status='ACTIVE'
                """, comment.location().authorId(), comment.location().id(), comment.publishedRevisionId()) == 1;
    }

    public PageResult<CommentView> list(Viewer viewer, long resource, Long rootId, int page, int size, int offset) {
        String where = " WHERE c.resource_id=? AND c.root_comment_id" + (rootId == null ? " IS NULL" : "=?");
        Object[] filter = rootId == null ? new Object[]{resource} : new Object[]{resource, rootId};
        long total = sql.count("SELECT COUNT(*) FROM community_comments c" + where, filter);
        String query = SELECT + JOIN + where + " ORDER BY c.created_at,c.id LIMIT ? OFFSET ?";
        List<CommentView> items = rootId == null
                ? sql.db.query(query, (rs, n) -> view(rs, viewer), resource, size, offset)
                : sql.db.query(query, (rs, n) -> view(rs, viewer), resource, rootId, size, offset);
        return new PageResult<>(page, size, total, items);
    }

    public CommentView view(Viewer viewer, long id) {
        var rows = sql.db.query(SELECT + JOIN + " WHERE c.id=?", (rs, n) -> view(rs, viewer), id);
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.get(0);
    }

    /** Current visibility controls whether the owner can receive the version produced by the receipt. */
    public CommentView writtenRevision(Viewer viewer, long comment, long revision) {
        var rows = sql.db.query(select("w") + JOIN + """
                 JOIN community_comment_revisions w ON w.comment_id=c.id AND w.id=? AND w.created_by=c.user_id
                     AND w.revision_state='APPROVED' AND w.release_method='DIRECT'
                 WHERE c.id=? AND c.user_id=?
                """, (rs, n) -> view(rs, viewer), revision, comment, viewer.id());
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.get(0);
    }

    private static CommentView view(ResultSet rs, Viewer viewer) throws SQLException {
        String state = rs.getString("display_state");
        boolean visible = "VISIBLE".equals(state);
        boolean own = rs.getLong("user_id") == viewer.id();
        boolean mayReply = visible && viewer.poster() && viewer.writable();
        boolean mayDelete = own && viewer.poster() && !"DELETED".equals(state);
        var capabilities = new CommentCapabilities(mayReply, mayReply && own, mayDelete);
        return new CommentView(rs.getString("id"), rs.getString("resource_id"), rs.getString("root_comment_id"),
                rs.getString("parent_comment_id"), visible ? rs.getString("revision_id") : null,
                visible ? person(rs.getString("user_id"), rs.getString("nickname"), rs.getString("avatar_path")) : null,
                visible ? rs.getString("body") : null, state, "PLAIN_TEXT",
                visible ? rs.getString("format_policy_id") : null, time(rs, "created_at"),
                visible ? time(rs, "edited_at") : null, rs.getLong("reply_count"), rs.getInt("lock_version"), capabilities);
    }

    public long create(long resource, long actor, Long root, Long parent) {
        return sql.insert("INSERT INTO community_comments(resource_id,user_id,root_comment_id,parent_comment_id) VALUES(?,?,?,?)",
                resource, actor, root, parent);
    }

    public int nextVersion(long comment) {
        return sql.db.queryForObject("SELECT MAX(version_no)+1 FROM community_comment_revisions WHERE comment_id=?", Integer.class, comment);
    }

    public long freeze(long comment, int version, Long base, String body, long policy, long actor) {
        return sql.insert("""
                INSERT INTO community_comment_revisions(comment_id,version_no,base_revision_id,body,revision_state,
                    release_method,format_policy_id,source_sha256,body_char_count,created_by)
                VALUES(?,?,?,?,'APPROVED','DIRECT',?,?,?,?)
                """, comment, version, base, body, policy, CommunitySourceHash.comment(body), CommunityTextValidator.chars(body), actor);
    }

    public void publish(long comment, long revision, boolean edited) {
        sql.db.update("UPDATE community_comments SET published_revision_id=?,lock_version=lock_version+? WHERE id=?",
                revision, edited ? 1 : 0, comment);
    }

    public void delete(long comment) {
        sql.db.update("""
                UPDATE community_comments SET status='DELETED',published_revision_id=NULL,candidate_revision_id=NULL,lock_version=lock_version+1
                WHERE id=?
                """, comment);
    }

    public void refreshReplyTime(long resource) {
        var last = sql.db.queryForObject("SELECT MAX(c.created_at) " + JOIN + " WHERE c.resource_id=? AND " + VISIBLE,
                java.time.LocalDateTime.class, resource);
        sql.db.update("UPDATE community_post_profiles SET last_public_comment_at=? WHERE resource_id=?", last, resource);
    }
}
