package cn.tcm.platform.community.infrastructure;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static cn.tcm.platform.community.infrastructure.CommunityPostRepository.nullableNumber;
import static cn.tcm.platform.community.infrastructure.CommunitySectionRepository.LEGACY;
import static cn.tcm.platform.community.infrastructure.CommunitySectionRepository.LEGACY_NAME;
import static cn.tcm.platform.content.ContentSupport.number;

@Repository
public class CommunityArticleRepository {
    private final ContentSupport sql;
    private final CommunityPostRepository posts;
    private final CommunitySectionRepository sections;

    public CommunityArticleRepository(ContentSupport sql, CommunityPostRepository posts, CommunitySectionRepository sections) {
        this.sql = sql;
        this.posts = posts;
        this.sections = sections;
    }

    public record Locked(Map<String, Object> resource, Map<String, Object> revision, Long publicSectionId,
                         Long sectionId, CommunitySectionRepository.Hierarchy targetSection) {}

    public Map<String, Object> own(long owner, long resource) {
        return owned(owner, resource, false);
    }

    private Map<String, Object> owned(long owner, long resource, boolean lock) {
        return sql.row("SELECT * FROM content_resources WHERE id=? AND owner_id=? AND resource_type='ARTICLE' "
                + "AND EXISTS (SELECT 1 FROM content_revisions v WHERE v.resource_id=content_resources.id)"
                + (lock ? " FOR UPDATE" : ""), resource, owner);
    }

    public Long sectionId(long resource) {
        var ids = sql.db.queryForList("SELECT section_id FROM community_post_profiles WHERE resource_id=?", Long.class, resource);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Called after account/receipt locking. Section IDs are locked together in key order. */
    public Locked lock(long owner, long resource, Long requestedSection, boolean ensureSection) {
        own(owner, resource);
        var located = posts.latest(resource);
        Long locatedRevisionSection = nullableNumber(located.get("section_id"));
        Long locatedPublicSection = sectionId(resource);
        Long section = locatedRevisionSection != null ? locatedRevisionSection : locatedPublicSection;
        if (section == null && ensureSection && requestedSection == null) section = ensureLegacySection();
        Long target = requestedSection == null ? section : requestedSection;
        var ids = new TreeSet<Long>();
        if (locatedPublicSection != null) ids.add(locatedPublicSection);
        if (section != null) ids.add(section);
        if (target != null) ids.add(target);
        var hierarchy = sections.lockHierarchies(ids);
        var locked = owned(owner, resource, true);
        var revision = posts.latestLocked(resource);
        if (!Objects.equals(located.get("id"), revision.get("id"))
                || !Objects.equals(locatedRevisionSection, nullableNumber(revision.get("section_id")))
                || !Objects.equals(locatedPublicSection, sectionId(resource))) {
            throw ApiException.conflict("文章版本或分区已变化，请刷新");
        }
        return new Locked(locked, revision, locatedPublicSection, section, target == null ? null : hierarchy.get(target));
    }

    public long ensureLegacySection() {
        var rows = sql.db.queryForList("SELECT * FROM community_sections WHERE code=?", LEGACY);
        if (rows.isEmpty()) {
            // The unique indexes serialize a concurrent bootstrap. Conflicting user rows are never adopted or rewritten.
            sql.db.update("""
                    INSERT INTO community_sections(code,name,normalized_name,description,active,lifecycle)
                    VALUES('legacy-content','历史内容','历史内容','历史未分区内容容器；隐藏导航且不接受新建内容',0,'FORMAL')
                    ON DUPLICATE KEY UPDATE id=id
                    """);
            rows = sql.db.queryForList("SELECT * FROM community_sections WHERE code=? FOR UPDATE", LEGACY);
        }
        if (rows.isEmpty()) throw ApiException.conflict("历史内容分区名称已被占用");
        var row = rows.get(0);
        if (!systemLegacy(row)) throw ApiException.conflict("系统保留的历史内容分区存在冲突");
        return number(row.get("id"));
    }

    public boolean isPublishedLegacy(Locked article) {
        Object revision = article.resource().get("published_revision_id");
        if (revision == null || article.publicSectionId() == null) return false;
        var rows = sql.db.queryForList("""
                SELECT s.*,p.release_method,p.source_format,v.revision_state
                FROM community_sections s
                JOIN community_revision_profiles p ON p.section_id=s.id
                JOIN content_revisions v ON v.id=p.revision_id AND v.resource_id=p.resource_id
                WHERE s.id=? AND p.resource_id=? AND p.revision_id=?
                """, article.publicSectionId(), article.resource().get("id"), revision);
        if (rows.isEmpty()) return false;
        var row = rows.get(0);
        return systemLegacy(row) && "LEGACY".equals(row.get("release_method"))
                && "PLAIN_TEXT".equals(row.get("source_format")) && "APPROVED".equals(row.get("revision_state"));
    }

    private static boolean systemLegacy(Map<String, Object> row) {
        return LEGACY.equals(row.get("code")) && LEGACY_NAME.equals(row.get("name"))
                && LEGACY_NAME.equals(row.get("normalized_name")) && row.get("parent_id") == null
                && row.get("owner_id") == null && row.get("created_by") == null
                && !CommunityPostRepository.bool(row.get("active")) && "FORMAL".equals(row.get("lifecycle"));
    }

    public void setSection(long resource, long section, boolean publish) {
        sql.db.update("""
                INSERT INTO community_post_profiles(resource_id,section_id,public_edited_at)
                VALUES(?,?,IF(?,UTC_TIMESTAMP(3),NULL)) AS incoming
                ON DUPLICATE KEY UPDATE section_id=incoming.section_id,
                  public_edited_at=COALESCE(incoming.public_edited_at,community_post_profiles.public_edited_at),
                  lock_version=community_post_profiles.lock_version+1
                """, resource, section, publish);
    }

    public void deleteDraft(long resource, long revision) {
        sql.db.update("DELETE FROM community_revision_references WHERE revision_id=?", revision);
        sql.db.update("DELETE FROM community_revision_profiles WHERE revision_id=?", revision);
        sql.db.update("DELETE FROM content_revisions WHERE id=?", revision);
        if (sql.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?", resource) != 0) return;
        sql.db.update("DELETE FROM community_post_profiles WHERE resource_id=?", resource);
        if (sql.count("SELECT COUNT(*) FROM community_quota_requests WHERE resource_id=?", resource) == 0) {
            sql.db.update("DELETE FROM content_resources WHERE id=?", resource);
            return;
        }
        // Preserve quota decisions and their audit links. A tombstone has no revision and cannot reopen.
        sql.db.update("""
                UPDATE community_quota_requests SET status='WITHDRAWN',lock_version=lock_version+1
                WHERE resource_id=? AND status='PENDING'
                """, resource);
        sql.db.update("UPDATE user_notifications SET relative_path=NULL WHERE resource_id=?", resource);
        sql.db.update("""
                UPDATE content_resources SET visibility_status='ARCHIVED',suspended_reason='作者已删除草稿' WHERE id=?
                """, resource);
    }
}
