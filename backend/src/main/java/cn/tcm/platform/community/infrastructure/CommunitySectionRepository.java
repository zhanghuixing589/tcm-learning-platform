package cn.tcm.platform.community.infrastructure;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.community.application.CommunitySourceHash;
import cn.tcm.platform.content.ContentSupport;
import org.slf4j.MDC;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

@Repository
public class CommunitySectionRepository {
    public static final String LEGACY = "legacy-content";
    public static final String LEGACY_NAME = "历史内容";
    private final ContentSupport sql;

    public CommunitySectionRepository(ContentSupport sql) { this.sql = sql; }

    public record Section(long id, Long parentId, String name, String code, String description, String rulesText,
                          Long ownerId, String lifecycle, String managementStatus, boolean active,
                          int sortOrder, int lockVersion) {
        public boolean own(long viewer) { return ownerId != null && ownerId == viewer; }
        public boolean writable() { return active && !"ARCHIVED".equals(lifecycle) && !LEGACY.equals(code); }
    }

    public record Hierarchy(Section section, Section parent) {
        public boolean writable() { return section.writable() && (parent == null || parent.writable()); }
        public void requireWritable() {
            if (!writable()) throw ApiException.conflict("分区当前为只读状态");
        }
    }

    public record Membership(boolean joined, long memberCount) {}
    public record SectionRow(Section section, boolean writable, Membership membership) {}
    private static final String SELECT = """
            SELECT s.*,
                (s.active=1 AND s.lifecycle<>'ARCHIVED' AND s.code<>'legacy-content'
                  AND (s.parent_id IS NULL OR (p.active=1 AND p.lifecycle<>'ARCHIVED' AND p.code<>'legacy-content'))) writable,
                EXISTS(SELECT 1 FROM community_section_members m WHERE m.section_id=s.id AND m.user_id=? AND m.state='JOINED') joined,
                (SELECT COUNT(*) FROM community_section_members m JOIN sys_users u ON u.id=m.user_id AND u.status='ACTIVE'
                 WHERE m.section_id=s.id AND m.state='JOINED') member_count
            FROM community_sections s LEFT JOIN community_sections p ON p.id=s.parent_id
            """;

    public Section find(long id) { return find(id, false); }

    private Section find(long id, boolean lock) {
        var rows = sql.db.query("SELECT * FROM community_sections WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> section(rs), id);
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.get(0);
    }

    private static Section section(ResultSet rs) throws SQLException {
        return new Section(rs.getLong("id"), rs.getObject("parent_id", Long.class), rs.getString("name"),
                rs.getString("code"), rs.getString("description"), rs.getString("rules_text"),
                rs.getObject("owner_id", Long.class), rs.getString("lifecycle"), rs.getString("management_status"),
                rs.getBoolean("active"), rs.getInt("sort_order"), rs.getInt("lock_version"));
    }

    private static SectionRow row(ResultSet rs, int index) throws SQLException {
        return new SectionRow(section(rs), rs.getBoolean("writable"), new Membership(rs.getBoolean("joined"), rs.getLong("member_count")));
    }

    public List<SectionRow> list(long actor, Long parent, boolean joinedOnly) {
        String condition = parent == null ? "s.parent_id IS NULL" : "s.parent_id=?";
        String query = SELECT + " WHERE s.code<>'legacy-content' AND " + condition
                + (joinedOnly ? " HAVING joined=1" : "") + " ORDER BY s.sort_order,s.id";
        return parent == null ? sql.db.query(query, CommunitySectionRepository::row, actor)
                : sql.db.query(query, CommunitySectionRepository::row, actor, parent);
    }

    public SectionRow view(long actor, long id) {
        var rows = sql.db.query(SELECT + " WHERE s.id=? AND s.code<>'legacy-content'",
                CommunitySectionRepository::row, actor, id);
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.get(0);
    }

    public Hierarchy lockHierarchy(long sectionId) {
        return lockHierarchies(List.of(sectionId)).get(sectionId);
    }

    public Map<Long, Hierarchy> lockHierarchies(Collection<Long> sectionIds) {
        var targets = new HashMap<Long, Section>();
        var all = new TreeSet<>(sectionIds);
        for (long id : sectionIds) {
            Section section = find(id);
            targets.put(id, section);
            Long parentId = section.parentId();
            if (parentId != null) {
                if (parentId == id) throw ApiException.invalid("分区不能以自身为父级");
                all.add(parentId);
            }
        }
        var locked = new HashMap<Long, Section>();
        for (long id : all) locked.put(id, find(id, true));
        var result = new HashMap<Long, Hierarchy>();
        for (var target : targets.values()) {
            Section section = locked.get(target.id());
            if (!Objects.equals(target.parentId(), section.parentId()))
                throw ApiException.conflict("分区归属已变化，请刷新");
            Section parent = section.parentId() == null ? null : locked.get(section.parentId());
            if (parent != null && parent.parentId() != null) throw ApiException.invalid("社区分区最多支持两级");
            result.put(section.id(), new Hierarchy(section, parent));
        }
        return result;
    }

    public long create(Long parent, String name, String normalized, String code, String description, String rules,
                       long actor, Long owner, int sortOrder) {
        return sql.insert("""
                INSERT INTO community_sections(parent_id,name,normalized_name,code,description,rules_text,created_by,owner_id,sort_order)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, parent, name, normalized, code, description, rules, actor, owner, sortOrder);
    }

    public void update(long id, String description, String rulesText, boolean active, int sortOrder) {
        sql.db.update("""
                UPDATE community_sections SET description=?,rules_text=?,active=?,sort_order=?,lock_version=lock_version+1 WHERE id=?
                """, description, rulesText, active, sortOrder, id);
    }

    public String membershipState(long section, long actor) {
        var states = sql.db.queryForList("SELECT state FROM community_section_members WHERE section_id=? AND user_id=? FOR UPDATE",
                String.class, section, actor);
        return states.isEmpty() ? null : states.get(0);
    }

    public void join(long section, long actor) {
        sql.db.update("""
                INSERT INTO community_section_members(section_id,user_id,state,joined_at) VALUES(?,?,'JOINED',UTC_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE state='JOINED',joined_at=UTC_TIMESTAMP(3),left_at=NULL,reason_code=NULL,lock_version=lock_version+1
                """, section, actor);
    }

    public void leave(long section, long actor) {
        sql.db.update("""
                UPDATE community_section_members SET state='LEFT',left_at=UTC_TIMESTAMP(3),reason_code=NULL,lock_version=lock_version+1
                WHERE section_id=? AND user_id=? AND state='JOINED'
                """, section, actor);
    }

    public void removeDisabledAccount(long actor, long user) {
        var ids = new TreeSet<>(sql.db.queryForList("""
                SELECT section_id FROM community_section_members WHERE user_id=? AND state='JOINED'
                UNION SELECT id FROM community_sections WHERE owner_id=?
                """, Long.class, user, user));
        var locked = ids.stream().map(id -> find(id, true)).toList();
        for (Section section : locked) {
            int removed = sql.db.update("""
                    UPDATE community_section_members SET state='REMOVED',left_at=UTC_TIMESTAMP(3),
                        reason_code='ACCOUNT_DISABLED',lock_version=lock_version+1
                    WHERE section_id=? AND user_id=? AND state='JOINED'
                    """, section.id(), user);
            if (removed != 0) event(section.id(), user, actor, "MEMBER_REMOVED", "ACCOUNT_DISABLED",
                    Map.of("state", "JOINED"), Map.of("state", "REMOVED"));
            if (section.own(user)) {
                sql.db.update("UPDATE community_sections SET owner_id=NULL,management_status='UNOWNED',lock_version=lock_version+1 WHERE id=?", section.id());
                event(section.id(), user, actor, "OWNER_REMOVED", "ACCOUNT_DISABLED",
                        Map.of("ownerId", Long.toString(user), "managementStatus", section.managementStatus()),
                        Map.of("managementStatus", "UNOWNED"));
            }
        }
    }

    public void event(long section, Long subject, long actor, String action, String reason, Object before, Object after) {
        String trace = MDC.get("traceId");
        sql.db.update("""
                INSERT INTO community_section_events(section_id,subject_user_id,actor_id,action,reason_code,before_value,after_value,event_key,trace_id)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, section, subject, actor, action, reason, before == null ? null : CommunitySourceHash.json(before),
                after == null ? null : CommunitySourceHash.json(after), UUID.randomUUID().toString(),
                trace == null ? UUID.randomUUID().toString() : trace);
    }
}
