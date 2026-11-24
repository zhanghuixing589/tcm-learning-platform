package cn.tcm.platform.content.application;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.common.PublishedKnowledge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Shared publication pointer, interaction counts, and browser history for resource readers. */
@Component
public class PublishedResourceAccess {
    public static final String JOINS = """
             FROM content_resources r JOIN sys_users o ON o.id=r.owner_id
             LEFT JOIN content_revisions p ON p.id=r.published_revision_id AND p.resource_id=r.id
            """;
    // 可见资源须已发布、正式修订已批准且作者活跃；知识还要求有效来源依据。
    public static final String AVAILABLE = "(r.visibility_status='PUBLISHED' AND p.id IS NOT NULL"
            + " AND p.revision_state='APPROVED' AND o.status='ACTIVE'"
            + " AND (r.resource_type<>'KNOWLEDGE' OR " + PublishedKnowledge.evidenceIsActive("p") + "))";
    // 有效获赞排除自己和失效账号；收藏另行统计。
    public static final String LIKES = """
            (SELECT COUNT(*) FROM resource_likes lk JOIN sys_users lu ON lu.id=lk.user_id AND lu.status='ACTIVE'
             WHERE lk.resource_id=r.id AND lk.user_id<>r.owner_id)
            """;
    public static final String BOOKMARKS = """
            (SELECT COUNT(*) FROM resource_bookmarks bk JOIN sys_users bu ON bu.id=bk.user_id AND bu.status='ACTIVE'
             WHERE bk.resource_id=r.id)
            """;
    private final JdbcTemplate db;

    public PublishedResourceAccess(JdbcTemplate db) { this.db = db; }

    public static boolean isCommunityResource(String type) {
        return "POST".equals(type) || "ARTICLE".equals(type);
    }

    public void lockActiveViewer(long viewer) {
        var statuses = db.queryForList("SELECT status FROM sys_users WHERE id=? FOR SHARE", String.class, viewer);
        if (statuses.isEmpty() || !"ACTIVE".equals(statuses.get(0))) throw ApiException.unauthorized();
    }

    // 浏览次数达到整数边界后封顶，防止累计溢出。
    public void recordView(long viewer, long resourceId, String resourceType) {
        if (isCommunityResource(resourceType)) lockActiveViewer(viewer);
        db.update("""
                INSERT INTO user_resource_history(user_id,resource_id,last_viewed_at) VALUES(?,?,UTC_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE last_viewed_at=UTC_TIMESTAMP(3),view_count=LEAST(view_count,2147483646)+1
                """, viewer, resourceId);
    }

    public static String excerpt(String body) {
        if (body == null) return null;
        int count = Math.min(180, body.codePointCount(0, body.length()));
        return body.substring(0, body.offsetByCodePoints(0, count));
    }
}
