package cn.tcm.platform.community.application;

import cn.tcm.platform.account.api.PersonalCenterDtos.ArticleInput;
import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.community.api.CommunityDtos.SectionRef;
import cn.tcm.platform.community.infrastructure.CommunityArticleRepository;
import cn.tcm.platform.community.infrastructure.CommunityArticleRepository.Locked;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository;
import cn.tcm.platform.community.infrastructure.CommunityPostRepository.Grantee;
import cn.tcm.platform.community.infrastructure.CommunitySectionRepository;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static cn.tcm.platform.community.api.CommunityArticleDtos.*;
import static cn.tcm.platform.community.application.CommunitySectionService.*;
import static cn.tcm.platform.community.infrastructure.CommunityPostRepository.*;
import static cn.tcm.platform.community.infrastructure.CommunitySectionRepository.LEGACY;
import static cn.tcm.platform.content.ContentSupport.*;

@Service
public class CommunityArticleService {
    private static final Set<String> REVISION_BASES = Set.of("APPROVED", "REJECTED", "WITHDRAWN");
    private final ContentSupport sql;
    private final CommunityArticleRepository articles;
    private final CommunityPostRepository posts;
    private final CommunitySectionRepository sections;
    private final CommunityReferenceService references;
    private final CommunityQuotaService quotas;
    private final CommunityTextValidator validator;
    private final CommunityTextPolicy policies;
    private final CommunityWriteGuard writes;

    public CommunityArticleService(ContentSupport sql, CommunityArticleRepository articles, CommunityPostRepository posts,
                                   CommunitySectionRepository sections, CommunityReferenceService references,
                                   CommunityQuotaService quotas, CommunityTextValidator validator,
                                   CommunityTextPolicy policies, CommunityWriteGuard writes) {
        this.sql = sql;
        this.articles = articles;
        this.posts = posts;
        this.sections = sections;
        this.references = references;
        this.quotas = quotas;
        this.validator = validator;
        this.policies = policies;
        this.writes = writes;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> own(long actor, long resource) {
        return view(actor, articles.own(actor, resource), policies.current());
    }

    // 作者侧视图分别返回当前稿和正式版，不把候选稿公开。
    private Map<String, Object> view(long actor, Map<String, Object> row, CommunityTextPolicy policy) {
        long resource = number(row.get("id"));
        var latest = posts.source(posts.latest(resource), true);
        boolean qualified = sql.has(actor, ARTICLE);
        boolean writable = editable(row);
        boolean isDraft = latest.state().equals("DRAFT");
        Long sectionId = sectionId(latest.sectionId());
        if (sectionId == null) sectionId = articles.sectionId(resource);
        boolean sectionWritable = false;
        if (sectionId != null) {
            var section = sections.find(sectionId);
            sectionWritable = section.writable() && (section.parentId() == null || sections.find(section.parentId()).writable());
        }
        var quota = quotas.summary(resource, policy);
        var warnings = dangling(latest.body(), latest.sourceFormat(), latest.references());
        var published = posts.published(grantee(actor), resource);
        Object publishedView = published;
        if (published == null && row.get("published_revision_id") != null
                && "PUBLISHED".equals(row.get("visibility_status")) && articles.sectionId(resource) == null) {
            // Older readers may encounter an unprofiled legacy row. Keep its approved source separate from the draft.
            var legacy = posts.revision(resource, number(row.get("published_revision_id")));
            if ("APPROVED".equals(legacy.get("revision_state"))) publishedView = posts.source(legacy, true);
        }
        var notes = sql.db.queryForList("""
                SELECT t.review_note FROM review_tasks t JOIN content_revisions v ON v.id=t.revision_id
                WHERE v.resource_id=? ORDER BY v.version_no DESC LIMIT 1
                """, resource);
        var capabilities = new ArticleCapabilities(qualified && writable && isDraft,
                qualified && writable && isDraft && sectionWritable && !blank(latest.body())
                        && latest.bodyCharCount() <= quota.bodyCharLimit() && warnings.isEmpty(),
                qualified && latest.state().equals("IN_REVIEW"),
                qualified && writable && REVISION_BASES.contains(latest.state()),
                qualified && writable && isDraft,
                qualified && !"ARCHIVED".equals(row.get("visibility_status"))
                        && quota.bodyCharLimit() < quota.bodyHardLimit()
                        && (quota.latestRequest() == null || !"PENDING".equals(quota.latestRequest().status())));
        return map("id", sid(resource), "resourceType", "ARTICLE", "title", latest.title(), "body", latest.body(),
                "revisionId", latest.revisionId(), "revisionState", latest.state(), "visibilityStatus", row.get("visibility_status"),
                "reviewNote", notes.isEmpty() ? null : notes.get(0).get("review_note"), "stateReason", row.get("suspended_reason"),
                "sectionId", sid(sectionId), "sourceFormat", latest.sourceFormat(), "references", latest.references(),
                "publishedRevisionId", sid(row.get("published_revision_id")), "published", publishedView, "latestRevision", latest,
                "lockVersion", row.get("lock_version"), "quota", quota, "capabilities", capabilities,
                "danglingReferenceNumbers", warnings);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> create(long actor, String key, ArticleInput input) {
        if (input.baseRevisionId() != null || input.lockVersion() != null)
            throw ApiException.invalid("新建草稿不接受版本锁");
        return create(actor, key, input.title(), input.body(), input.sourceFormat(), sectionId(input.sectionId()),
                input.references() == null ? List.of() : input.references(), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> draftIdentity(long actor, String key, DraftIdentity input) {
        return create(actor, key, input.title(), "", input.sourceFormat(), null, List.of(), true);
    }

    private Map<String, Object> create(long actor, String key, String rawTitle, String rawBody, String rawFormat,
                                       Long sectionId, List<CommunityReference> refs, boolean identityOnly) {
        String body = CommunityTextValidator.lf(rawBody);
        String format = checkedFormat(rawFormat, "PLAIN_TEXT");
        String hash = CommunitySourceHash.canonical(map("title", rawTitle == null ? null : rawTitle.strip(),
                "body", body, "sourceFormat", format, "sectionId", sectionId, "references", ordered(refs)));
        return writes.executeLocked(actor, List.of(), identityOnly ? "ARTICLE_DRAFT_IDENTITY" : "ARTICLE_CREATE",
                key, hash, "DRAFT_SAVE", () -> sql.require(actor, ARTICLE), gate -> {
                    long section = sectionId == null ? articles.ensureLegacySection() : sectionId;
                    var hierarchy = sections.lockHierarchy(section);
                    if (sectionId != null) hierarchy.requireWritable();
                    references.lockAssignments(refs);
                    var policy = gate.policy();
                    String title = validator.title(rawTitle, null, policy);
                    if (!identityOnly) validator.body(body, format, quotas.bodyLimit(0, policy), policy);
                    references.validate(0, body, format, refs, policy, false);
                    gate.charge();
                    long resource = sql.insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('ARTICLE',?)", actor);
                    long revision = insertRevision(actor, resource, 1, null, title, body);
                    articles.setSection(resource, section, false);
                    saveSnapshot(resource, revision, section, title, body, format, policy, "NONE", refs);
                    sql.audit(actor, identityOnly ? "ARTICLE_DRAFT_IDENTITY" : "ARTICLE_DRAFT_CREATED", "ARTICLE", resource);
                    return new CommunityWriteGuard.Result("ARTICLE", resource, 201);
                }, result -> own(actor, result.id()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> update(long actor, long resource, ArticleInput input) {
        var version = version(input.baseRevisionId(), input.lockVersion());
        Long requested = sectionId(input.sectionId());
        return writes.executeUnkeyed(actor, "DRAFT_SAVE", gate -> {
            sql.require(actor, ARTICLE);
            Locked target = articles.lock(actor, resource, requested, true);
            requireVersion(target, version);
            requireEditable(target.resource());
            requireState(target, "DRAFT");
            long section = target.targetSection().section().id();
            if (requested != null && !Objects.equals(requested, target.sectionId())) target.targetSection().requireWritable();
            requirePublicationSection(target);
            long revision = revisionId(target);
            var saved = posts.source(target.revision(), true);
            var refs = input.references() == null ? saved.references() : input.references();
            references.lockAssignments(refs);
            var policy = gate.policy();
            String format = checkedFormat(input.sourceFormat(), saved.sourceFormat());
            String title = validator.title(input.title(), saved.title(), policy);
            String body = validator.body(input.body(), format, quotas.bodyLimit(resource, policy), policy);
            references.validate(resource, body, format, refs, policy, false);
            CommunityReferences.preventCitationSubstitution(saved.body(), saved.references(), saved.sourceFormat(), body, refs, format);
            gate.charge();
            sql.db.update("UPDATE content_revisions SET title=?,body=? WHERE id=?", title, body, revision);
            if (target.resource().get("published_revision_id") == null
                    && !Objects.equals(target.publicSectionId(), section)) articles.setSection(resource, section, false);
            saveSnapshot(resource, revision, section, title, body, format, policy, "NONE", refs);
            bump(resource);
            return view(actor, articles.own(actor, resource), policy);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> reorder(long actor, long resource, String key, ReferenceOrder input) {
        var version = version(input.baseRevisionId(), input.lockVersion());
        return command(actor, resource, key, "REFERENCES_REORDER", version, input.referenceKeys(), "DRAFT_SAVE", gate -> {
            var target = articles.lock(actor, resource, null, true);
            requireVersion(target, version);
            requireEditable(target.resource());
            requireState(target, "DRAFT");
            var saved = posts.source(target.revision(), true);
            var reordered = CommunityReferences.reorder(saved.body(), saved.references(), input.referenceKeys(), saved.sourceFormat());
            references.lockAssignments(reordered.references());
            var policy = gate.policy();
            validator.body(reordered.body(), saved.sourceFormat(), quotas.bodyLimit(resource, policy), policy);
            references.validate(resource, reordered.body(), saved.sourceFormat(), reordered.references(), policy, false);
            gate.charge();
            sql.db.update("UPDATE content_revisions SET body=? WHERE id=?", reordered.body(), revisionId(target));
            saveSnapshot(resource, revisionId(target), target.sectionId(), saved.title(), reordered.body(), saved.sourceFormat(),
                    policy, "NONE", reordered.references());
            bump(resource);
            return revisionId(target);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> submit(long actor, long resource, String key, RevisionLock input) {
        var version = version(input.baseRevisionId(), input.lockVersion());
        return command(actor, resource, key, "SUBMIT", version, null, "DRAFT_SAVE", gate -> {
            var target = articles.lock(actor, resource, null, true);
            requireVersion(target, version);
            requireEditable(target.resource());
            requireState(target, "DRAFT");
            var saved = posts.source(target.revision(), true);
            if (blank(saved.body())) throw ApiException.invalid("正文不能为空");
            if (LEGACY.equals(target.targetSection().section().code())) throw ApiException.invalid("提交审核前请选择可写分区");
            target.targetSection().requireWritable();
            requirePublicationSection(target);
            references.lockAssignments(saved.references());
            var policy = gate.policy();
            String body = validator.body(saved.body(), saved.sourceFormat(), quotas.bodyLimit(resource, policy), policy);
            references.validate(resource, body, saved.sourceFormat(), saved.references(), policy, true);
            gate.charge();
            long revision = revisionId(target);
            saveSnapshot(resource, revision, target.sectionId(), saved.title(), body, saved.sourceFormat(), policy,
                    "NONE", saved.references());
            sql.db.update("UPDATE content_revisions SET body=?,revision_state='IN_REVIEW',submitted_at=UTC_TIMESTAMP(3) WHERE id=?",
                    body, revision);
            sql.db.update("INSERT INTO review_tasks(revision_id,submitted_by) VALUES(?,?)", revision, actor);
            sql.audit(actor, "ARTICLE_SUBMITTED", "ARTICLE", resource);
            bump(resource);
            return revision;
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    // 撤回仅处理本人冻结投稿，保留任务事件；后续修改通过新的修订进行。
    public Map<String, Object> withdraw(long actor, long resource, String key, RevisionLock input) {
        var version = version(input.baseRevisionId(), input.lockVersion());
        return command(actor, resource, key, "WITHDRAW", version, null, null, gate -> {
            var target = articles.lock(actor, resource, null, false);
            requireVersion(target, version);
            requireState(target, "IN_REVIEW");
            long revision = revisionId(target);
            var task = sql.row("SELECT id,status FROM review_tasks WHERE revision_id=? FOR UPDATE", revision);
            if (!"PENDING".equals(task.get("status"))) throw ApiException.conflict("审核任务已处理");
            sql.db.update("UPDATE review_tasks SET status='WITHDRAWN',lock_version=lock_version+1 WHERE id=?", task.get("id"));
            sql.db.update("INSERT INTO review_events(review_task_id,actor_id,action) VALUES(?,?,'WITHDRAW')", task.get("id"), actor);
            sql.db.update("UPDATE content_revisions SET revision_state='WITHDRAWN' WHERE id=?", revision);
            bump(resource);
            return revision;
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> revise(long actor, long resource, String key, RevisionLock input) {
        var version = version(input.baseRevisionId(), input.lockVersion());
        return command(actor, resource, key, "REVISE", version, null, "DRAFT_SAVE", gate -> {
            var target = articles.lock(actor, resource, null, true);
            requireVersion(target, version);
            requireEditable(target.resource());
            var saved = posts.source(target.revision(), true);
            if (!REVISION_BASES.contains(saved.state())) throw ApiException.conflict("请先完成当前修订");
            references.lockAssignments(saved.references());
            var policy = gate.policy();
            String body = validator.body(saved.body(), saved.sourceFormat(), quotas.bodyLimit(resource, policy), policy);
            references.validate(resource, body, saved.sourceFormat(), saved.references(), policy, false);
            gate.charge();
            long revision = insertRevision(actor, resource, saved.versionNo() + 1, revisionId(target), saved.title(), body);
            if (target.publicSectionId() == null) articles.setSection(resource, target.sectionId(), false);
            saveSnapshot(resource, revision, target.sectionId(), saved.title(), body, saved.sourceFormat(), policy, "NONE", saved.references());
            bump(resource);
            return revision;
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(long actor, long resource, String baseRevisionId, Integer lockVersion) {
        var version = version(baseRevisionId, lockVersion);
        writes.executeUnkeyed(actor, null, gate -> {
            sql.require(actor, ARTICLE);
            var target = articles.lock(actor, resource, null, false);
            requireVersion(target, version);
            requireEditable(target.resource());
            requireState(target, "DRAFT");
            articles.deleteDraft(resource, revisionId(target));
            bump(resource);
            sql.audit(actor, "ARTICLE_DRAFT_DELETED", "ARTICLE", resource);
            return null;
        });
    }

    private Map<String, Object> command(long actor, long resource, String key, String operation, RevisionLock version,
                                        Object payload, String rate, Function<CommunityWriteGuard.RateGate, Long> action) {
        var hash = CommunitySourceHash.canonical(map("version", version, "payload", payload));
        return writes.executeLocked(actor, List.of(), "ARTICLE_" + operation + ":" + resource, key, hash, rate,
                () -> {
                    sql.require(actor, ARTICLE);
                    articles.own(actor, resource);
                }, gate -> new CommunityWriteGuard.Result("ARTICLE_REVISION", action.apply(gate), 200),
                result -> commandView(actor, resource, result.id()));
    }

    private Map<String, Object> commandView(long actor, long resource, long revision) {
        var current = own(actor, resource);
        if (sid(revision).equals(current.get("revisionId"))) {
            current.put("staleResult", false);
            return current;
        }
        var original = posts.source(posts.revision(resource, revision), true);
        var latestCapabilities = (ArticleCapabilities) current.get("capabilities");
        var notes = sql.db.queryForList("SELECT review_note FROM review_tasks WHERE revision_id=?", revision);
        current.putAll(map("staleResult", true, "resultRevision", original, "revisionId", original.revisionId(),
                "revisionState", original.state(), "title", original.title(), "body", original.body(),
                "sourceFormat", original.sourceFormat(), "sectionId", original.sectionId(), "references", original.references(),
                "reviewNote", notes.isEmpty() ? null : notes.get(0).get("review_note"),
                "danglingReferenceNumbers", dangling(original.body(), original.sourceFormat(), original.references()),
                "capabilities", new ArticleCapabilities(false, false, false, false, false, latestCapabilities.canRequestQuota())));
        return current;
    }

    public Locked lockReview(long actor, long author, long resource, long revision) {
        writes.lockAccounts(actor, List.of(author));
        sql.require(actor, MODERATE);
        Long section = nullableNumber(posts.revision(resource, revision).get("section_id"));
        return articles.lock(author, resource, section, false);
    }

    public void requireReviewTarget(Locked article, long author, long revision, boolean approve) {
        if (revisionId(article) != revision) throw ApiException.conflict("审核目标已变化，请刷新");
        requireState(article, "IN_REVIEW");
        if (!approve) return;
        requireEditable(article.resource());
        if (sql.count("SELECT COUNT(*) FROM sys_users WHERE id=? AND status='ACTIVE'", author) != 1
                || !sql.has(author, ARTICLE)) throw ApiException.conflict("作者已失去投稿资格");
        if (article.targetSection() == null) throw ApiException.conflict("文章尚未分类，请撤回后选择可写分区");
        article.targetSection().requireWritable();
        requirePublicationSection(article);
    }

    private void requirePublicationSection(Locked article) {
        if (article.resource().get("published_revision_id") == null) return;
        var target = article.targetSection();
        if (Objects.equals(article.publicSectionId(), target.section().id())) return;
        // A migration-only placeholder can gain its first formal section through the ordinary approval path.
        if ("FORMAL".equals(target.section().lifecycle()) && target.writable() && articles.isPublishedLegacy(article)) return;
        throw ApiException.conflict("已公开文章不能自行变更分区");
    }

    public void completeReview(Locked article, long resource, long revision, boolean approved) {
        if (approved) {
            sql.db.update("UPDATE community_revision_profiles SET release_method='MANUAL_REVIEW' WHERE revision_id=?", revision);
            articles.setSection(resource, article.targetSection().section().id(), true);
            sql.db.update("""
                    UPDATE content_resources SET published_revision_id=?,visibility_status='PUBLISHED',
                        published_at=COALESCE(published_at,UTC_TIMESTAMP(3)),lock_version=lock_version+1 WHERE id=?
                    """, revision, resource);
        } else {
            bump(resource);
        }
    }

    public Map<String, Object> frozen(long resource, long revision) {
        var source = posts.source(posts.revision(resource, revision), true);
        SectionRef sectionRef = null;
        if (source.sectionId() != null) {
            var section = sections.find(id(source.sectionId()));
            sectionRef = new SectionRef(sid(section.id()), sid(section.parentId()), section.code(), section.name());
        }
        return map("id", sid(resource), "revisionId", source.revisionId(), "title", source.title(), "body", source.body(),
                "revisionState", source.state(), "section", sectionRef, "sectionId", source.sectionId(),
                "sourceFormat", source.sourceFormat(), "formatPolicyId", source.formatPolicyId(),
                "sourceHash", source.sourceHash(), "references", source.references(), "versionNo", source.versionNo());
    }

    private long insertRevision(long actor, long resource, int version, Long base, String title, String body) {
        return sql.insert("""
                INSERT INTO content_revisions(resource_id,version_no,base_revision_id,title,body,created_by)
                VALUES(?,?,?,?,?,?)
                """, resource, version, base, title, body, actor);
    }

    private void saveSnapshot(long resource, long revision, long section, String title, String body, String format,
                              CommunityTextPolicy policy, String release, List<CommunityReference> refs) {
        sql.db.update("""
                INSERT INTO community_revision_profiles(revision_id,resource_id,section_id,source_format,format_policy_id,
                    release_method,source_sha256,body_char_count) VALUES(?,?,?,?,?,?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE section_id=incoming.section_id,source_format=incoming.source_format,
                    format_policy_id=incoming.format_policy_id,release_method=incoming.release_method,
                    source_sha256=incoming.source_sha256,body_char_count=incoming.body_char_count,
                    lock_version=community_revision_profiles.lock_version+1
                """, revision, resource, section, format, policy.id(), release,
                CommunitySourceHash.content(title, body, Long.toString(section), format, refs), CommunityTextValidator.chars(body));
        references.replace(revision, refs);
    }

    private static void requireVersion(Locked target, RevisionLock version) {
        if (!sid(revisionId(target)).equals(version.baseRevisionId())
                || number(target.resource().get("lock_version")) != version.lockVersion())
            throw ApiException.conflict("文章版本已变化，请刷新后重试");
    }

    private static RevisionLock version(String revision, Integer lockVersion) {
        if (revision == null || revision.isBlank() || lockVersion == null || lockVersion < 0)
            throw ApiException.invalid("缺少文章版本锁，请刷新编辑页后重试");
        return new RevisionLock(sid(id(revision)), lockVersion);
    }

    private static Long sectionId(String value) { return value == null ? null : id(value); }
    private static long revisionId(Locked target) { return number(target.revision().get("id")); }

    private static void requireState(Locked target, String state) {
        if (!state.equals(target.revision().get("revision_state"))) throw ApiException.conflict("当前修订状态不允许此操作");
    }

    private static boolean editable(Map<String, Object> article) {
        return Set.of("DRAFT", "PUBLISHED").contains(article.get("visibility_status"));
    }

    private static void requireEditable(Map<String, Object> article) {
        if (!editable(article)) throw ApiException.conflict("文章已暂停或归档");
    }

    private void bump(long resource) {
        sql.db.update("UPDATE content_resources SET lock_version=lock_version+1 WHERE id=?", resource);
    }

    private Grantee grantee(long actor) { return new Grantee(actor, sql.has(actor, POST), sql.has(actor, MODERATE)); }

    private static boolean blank(String text) { return text.codePoints().allMatch(CommunityTextValidator::whitespace); }

    private static Set<Integer> dangling(String body, String format, List<CommunityReference> refs) {
        var numbers = new java.util.HashSet<Integer>();
        refs.forEach(ref -> numbers.add(ref.displayNumber()));
        var unresolved = new java.util.TreeSet<Integer>();
        CommunityReferences.citations(body, format).forEach(citation -> {
            if (!numbers.contains(citation.number())) unresolved.add(citation.number());
        });
        return unresolved;
    }

    private static List<CommunityReference> ordered(List<CommunityReference> refs) {
        if (refs.stream().anyMatch(ref -> ref == null || ref.referenceKey() == null))
            throw ApiException.invalid("文献及稳定键不能为空");
        return refs.stream().sorted(Comparator.comparing(CommunityReference::referenceKey)).toList();
    }

    private static String checkedFormat(String raw, String fallback) {
        String value = raw == null ? fallback : raw;
        if (!Set.of("PLAIN_TEXT", "MARKDOWN").contains(value)) throw ApiException.invalid("正文格式无效");
        return value;
    }
}
