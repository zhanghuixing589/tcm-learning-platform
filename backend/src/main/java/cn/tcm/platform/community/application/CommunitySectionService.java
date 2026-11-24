package cn.tcm.platform.community.application;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.community.infrastructure.CommunitySectionRepository;
import cn.tcm.platform.community.infrastructure.CommunitySectionRepository.Section;
import cn.tcm.platform.community.infrastructure.CommunitySectionRepository.SectionRow;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.tcm.platform.community.api.CommunityDtos.*;
import static cn.tcm.platform.community.infrastructure.CommunitySectionRepository.LEGACY;
import static cn.tcm.platform.community.infrastructure.CommunitySectionRepository.LEGACY_NAME;
import static cn.tcm.platform.content.ContentSupport.id;
import static cn.tcm.platform.content.ContentSupport.map;

@Service
public class CommunitySectionService {
    public static final String POST = "community:post";
    public static final String ARTICLE = "community:article:submit";
    public static final String MODERATE = "community:moderate";
    public static final String MANAGE = "community:section:manage";
    private final CommunitySectionRepository sections;
    private final ContentSupport sql;
    private final CommunityWriteGuard writes;

    public CommunitySectionService(CommunitySectionRepository sections, ContentSupport sql, CommunityWriteGuard writes) {
        this.sections = sections;
        this.sql = sql;
        this.writes = writes;
    }

    private record Grants(boolean post, boolean article, boolean manage, boolean moderate) {}
    private Grants grants(long viewer) {
        return new Grants(sql.has(viewer, POST), sql.has(viewer, ARTICLE), sql.has(viewer, MANAGE), sql.has(viewer, MODERATE));
    }

    public List<SectionView> list(long actor, Long parentId, boolean joinedOnly) {
        requireActive(actor);
        if (parentId != null && LEGACY.equals(sections.find(parentId).code())) throw ApiException.missing();
        Grants grants = grants(actor);
        return sections.list(actor, parentId, joinedOnly).stream().map(row -> view(actor, row, grants)).toList();
    }

    public SectionView detail(long actor, long id) {
        requireActive(actor);
        return view(actor, sections.view(actor, id), grants(actor));
    }

    private static SectionView view(long actor, SectionRow row, Grants grants) {
        Section s = row.section();
        boolean joined = row.membership().joined();
        boolean writable = row.writable();
        boolean owner = s.own(actor);
        var capabilities = new SectionCapabilities(writable && grants.post() && !joined,
                grants.post() && joined && !owner, writable && owner, grants.manage(),
                writable && (owner || grants.moderate()), writable && grants.post(), writable && grants.article());
        return new SectionView(Long.toString(s.id()), ContentSupport.sid(s.parentId()), s.name(), s.code(), s.description(),
                s.rulesText(), ContentSupport.sid(s.ownerId()), s.lifecycle(), s.managementStatus(), s.active(),
                writable, s.sortOrder(), s.lockVersion(), joined, row.membership().memberCount(), capabilities);
    }

    public SectionView create(long actor, String key, SectionCreate input) {
        Long owner = input.ownerId() == null ? null : id(input.ownerId());
        Long parent = input.parentId() == null ? null : id(input.parentId());
        return writes.executeLocked(actor, owner == null ? List.of() : List.of(owner), "SECTION_CREATE", key,
                CommunitySourceHash.canonical(input), null, () -> sql.require(actor, MANAGE), gate -> {
                    if (owner != null && !active(owner)) throw ApiException.conflict("负责人账号当前不可用");
                    if (parent != null) {
                        var hierarchy = sections.lockHierarchy(parent);
                        if (hierarchy.parent() != null) throw ApiException.invalid("社区分区最多支持两级");
                        hierarchy.requireWritable();
                    }
                    String normalized = CommunityTextValidator.normalizeName(input.name());
                    if (CommunityTextValidator.chars(input.name()) > 80 || normalized.isEmpty() || CommunityTextValidator.chars(normalized) > 160)
                        throw ApiException.invalid("分区名称最多80字符，规范化名称最多160字符");
                    if (parent == null && LEGACY_NAME.equals(normalized)) throw ApiException.invalid("分区名称为系统保留名称");
                    String code = input.code();
                    if (!code.matches("[a-z0-9][a-z0-9_-]{0,63}") || LEGACY.equals(code))
                        throw ApiException.invalid("分区编码无效");
                    String description = field(input.description(), 500);
                    String rules = field(input.rulesText(), 4000);
                    gate.charge();
                    long sectionId;
                    try {
                        sectionId = sections.create(parent, input.name().strip(), normalized, code, description, rules,
                                actor, owner, input.sortOrder());
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("同级分区已存在相同名称或编码");
                    }
                    sections.event(sectionId, owner, actor, "SECTION_CREATED", null, null, Map.of("code", code));
                    return new CommunityWriteGuard.Result("SECTION", sectionId, 201);
                }, result -> detail(actor, result.id()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SectionView update(long actor, long id, SectionUpdate input) {
        lockActive(actor);
        sql.require(actor, MANAGE);
        Section s = visible(sections.lockHierarchy(id).section());
        change(actor, s, input.lockVersion(), input.description(), input.rulesText(), input.active(), input.sortOrder());
        return detail(actor, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SectionView profile(long actor, long id, SectionProfile input) {
        lockActive(actor);
        var hierarchy = sections.lockHierarchy(id);
        Section s = visible(hierarchy.section());
        if (!s.own(actor)) throw new ApiException(403, "FORBIDDEN", "仅本区负责人可以维护分区资料");
        hierarchy.requireWritable();
        change(actor, s, input.lockVersion(), input.description(), input.rulesText(), null, null);
        return detail(actor, id);
    }

    private void change(long actor, Section s, int version, String description, String rules, Boolean active, Integer order) {
        requireVersion(s.lockVersion(), version);
        String newDescription = description == null ? s.description() : field(description, 500);
        String newRules = rules == null ? s.rulesText() : field(rules, 4000);
        boolean newActive = active == null ? s.active() : active;
        int newOrder = order == null ? s.sortOrder() : order;
        if (Objects.equals(newDescription, s.description()) && Objects.equals(newRules, s.rulesText())
                && s.active() == newActive && s.sortOrder() == newOrder) return;
        sections.update(s.id(), newDescription, newRules, newActive, newOrder);
        sections.event(s.id(), null, actor, "SECTION_UPDATED", null,
                map("description", s.description(), "rulesText", s.rulesText(), "active", s.active(), "sortOrder", s.sortOrder(), "lockVersion", version),
                map("description", newDescription, "rulesText", newRules, "active", newActive, "sortOrder", newOrder, "lockVersion", version + 1));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MembershipView membership(long actor, long id, boolean join) {
        lockActive(actor);
        sql.require(actor, POST);
        var hierarchy = sections.lockHierarchy(id);
        Section section = visible(hierarchy.section());
        String state = sections.membershipState(id, actor);
        boolean joined = "JOINED".equals(state);
        if (!join && section.own(actor)) throw ApiException.conflict("分区负责人退出前请联系管理员交接");
        if (join && !joined) {
            hierarchy.requireWritable();
            sections.join(id, actor);
            sections.event(id, actor, actor, "MEMBER_JOINED", null,
                    state == null ? null : Map.of("state", state), Map.of("state", "JOINED"));
        } else if (!join && joined) {
            sections.leave(id, actor);
            sections.event(id, actor, actor, "MEMBER_LEFT", null, Map.of("state", "JOINED"), Map.of("state", "LEFT"));
        }
        var membership = sections.view(actor, id).membership();
        return new MembershipView(Long.toString(id), membership.joined(), membership.memberCount());
    }

    static Section visible(Section section) {
        if (LEGACY.equals(section.code())) throw ApiException.missing();
        return section;
    }

    boolean active(long actor) {
        return sql.count("SELECT COUNT(*) FROM sys_users WHERE id=? AND status='ACTIVE'", actor) == 1;
    }

    void requireActive(long actor) {
        if (!active(actor)) throw ApiException.unauthorized();
    }

    void lockActive(long actor) {
        var status = sql.db.queryForList("SELECT status FROM sys_users WHERE id=? FOR UPDATE", String.class, actor);
        if (status.isEmpty() || !"ACTIVE".equals(status.get(0))) throw ApiException.unauthorized();
    }

    static void requireVersion(int current, int requested) {
        if (current != requested) throw ApiException.conflict("版本已更新，请刷新后重试");
    }

    static String field(String value, int max) {
        if (value == null || value.isEmpty()) return null;
        String text = CommunityTextValidator.lf(value);
        if (CommunityTextValidator.chars(text) > max) throw ApiException.invalid("文本最多" + max + "字符");
        return text;
    }
}
