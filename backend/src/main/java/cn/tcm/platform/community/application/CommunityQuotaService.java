package cn.tcm.platform.community.application;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.common.PageResult;
import cn.tcm.platform.community.infrastructure.CommunityArticleRepository;
import cn.tcm.platform.content.ContentSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.tcm.platform.community.api.CommunityArticleDtos.*;
import static cn.tcm.platform.community.application.CommunitySectionService.ARTICLE;
import static cn.tcm.platform.content.ContentSupport.*;

@Service
public class CommunityQuotaService {
    private static final String MANAGE = "community:quota:manage";
    private final ContentSupport sql;
    private final CommunityArticleRepository articles;
    private final CommunityTextPolicy policies;
    private final CommunityWriteGuard writes;

    public CommunityQuotaService(ContentSupport sql, CommunityArticleRepository articles,
                                 CommunityTextPolicy policies, CommunityWriteGuard writes) {
        this.sql = sql;
        this.articles = articles;
        this.policies = policies;
        this.writes = writes;
    }

    public int bodyLimit(long resource, CommunityTextPolicy policy) {
        int defaultLimit = policy.limit("maxArticleChars");
        if (resource == 0) return defaultLimit;
        var limits = sql.db.queryForList("""
                SELECT q.body_char_limit FROM community_article_quotas q
                JOIN community_quota_requests qr ON qr.id=q.last_request_id AND qr.resource_id=q.resource_id
                  AND qr.status='APPROVED' AND qr.approved_limit=q.body_char_limit
                WHERE q.resource_id=?
                """, Integer.class, resource);
        int exception = limits.isEmpty() ? 0 : limits.get(0);
        return Math.min(policy.limit("maxArticleHardChars"), Math.max(defaultLimit, exception));
    }

    @Transactional(readOnly = true)
    public QuotaView view(long actor, long resource) {
        articles.own(actor, resource);
        return summary(resource, policies.current());
    }

    public QuotaView summary(long resource, CommunityTextPolicy policy) {
        var list = sql.db.queryForList("SELECT * FROM community_quota_requests WHERE resource_id=? ORDER BY id DESC LIMIT 1", resource);
        return new QuotaView(bodyLimit(resource, policy), policy.limit("maxArticleHardChars"),
                policy.limit("maxReferences"), policy.limit("maxReferenceChars"), list.isEmpty() ? null : view(list.get(0)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuotaRequestView request(long actor, long resource, String key, QuotaInput input) {
        String reason = bounded(input.reason());
        String hash = CommunitySourceHash.canonical(map("requestedLimit", input.requestedLimit(), "reason", reason));
        return writes.executeLocked(actor, List.of(), "ARTICLE_QUOTA_REQUEST:" + resource, key, hash, "QUOTA_REQUEST",
                () -> {
                    sql.require(actor, ARTICLE);
                    requireEligible(articles.own(actor, resource));
                }, gate -> {
                    var article = articles.lock(actor, resource, null, false);
                    requireEligible(article.resource());
                    var policy = gate.policy();
                    int requested = input.requestedLimit();
                    if (requested <= bodyLimit(resource, policy) || requested > policy.limit("maxArticleHardChars")) {
                        throw ApiException.invalid("申请额度必须高于当前额度且不超过硬上限");
                    }
                    if (sql.count("SELECT COUNT(*) FROM community_quota_requests WHERE pending_slot=?", resource) > 0)
                        throw ApiException.conflict("该文章已有待处理额度申请");
                    gate.charge();
                    long id = sql.insert("""
                            INSERT INTO community_quota_requests(resource_id,requested_by,requested_limit,reason)
                            VALUES(?,?,?,?)
                            """, resource, actor, requested, reason);
                    sql.audit(actor, "ARTICLE_QUOTA_REQUEST", "COMMUNITY_QUOTA", id);
                    return new CommunityWriteGuard.Result("ARTICLE_QUOTA", id, 201);
                }, result -> {
                    var row = row(result.id(), false);
                    if (number(row.get("requested_by")) != actor || number(row.get("resource_id")) != resource)
                        throw ApiException.missing();
                    return view(row);
                });
    }

    @Transactional(readOnly = true)
    public PageResult<QuotaRequestView> history(long actor, long resource, int page, int size) {
        articles.own(actor, resource);
        return page(" WHERE resource_id=? AND requested_by=?", page, size, List.of(resource, actor));
    }

    @Transactional(readOnly = true)
    public PageResult<QuotaRequestView> list(long actor, String status, int page, int size) {
        sql.require(actor, MANAGE);
        if (!Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN", "ALL").contains(status))
            throw ApiException.invalid("额度申请状态无效");
        return "ALL".equals(status) ? page("", page, size, List.of()) : page(" WHERE status=?", page, size, List.of(status));
    }

    private PageResult<QuotaRequestView> page(String condition, int page, int size, List<?> filters) {
        int offset = PageResult.offset(page, size);
        var args = new java.util.ArrayList<Object>(filters);
        long total = sql.count("SELECT COUNT(*) FROM community_quota_requests" + condition, args.toArray());
        args.add(size);
        args.add(offset);
        var rows = sql.db.queryForList("SELECT * FROM community_quota_requests" + condition
                + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        return new PageResult<>(page, size, total, rows.stream().map(CommunityQuotaService::view).toList());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuotaRequestView decide(long actor, long request, String key, QuotaDecision input) {
        sql.require(actor, MANAGE);
        var located = row(request, false);
        long author = number(located.get("requested_by"));
        long resource = number(located.get("resource_id"));
        String decision = input.decision();
        if (!Set.of("APPROVED", "REJECTED").contains(decision)) throw ApiException.invalid("额度决策无效");
        boolean approve = "APPROVED".equals(decision);
        if (approve != (input.approvedLimit() != null)) throw ApiException.invalid("仅批准时需要填写批准额度");
        String note = bounded(input.note());
        String hash = CommunitySourceHash.canonical(map("lockVersion", input.lockVersion(), "decision", decision,
                "approvedLimit", input.approvedLimit(), "note", note));
        return writes.executeLocked(actor, List.of(author), "ARTICLE_QUOTA_DECIDE:" + request, key, hash, null,
                () -> {
                    sql.require(actor, MANAGE);
                    if (actor == author) throw new ApiException(403, "SELF_REVIEW_FORBIDDEN", "不能审批本人额度申请");
                    articles.own(author, resource);
                }, gate -> {
                    var article = articles.lock(author, resource, null, false);
                    var current = row(request, true);
                    if (number(current.get("requested_by")) != author || number(current.get("resource_id")) != resource)
                        throw ApiException.conflict("额度申请关联已变化，请刷新");
                    if (!"PENDING".equals(current.get("status"))) {
                        if (decision.equals(current.get("status")) && Long.toString(actor).equals(sid(current.get("reviewer_id")))
                                && Objects.equals(input.approvedLimit(), current.get("approved_limit"))
                                && note.equals(current.get("review_note"))) {
                            return new CommunityWriteGuard.Result("ARTICLE_QUOTA", request, 200);
                        }
                        throw ApiException.conflict("额度申请已处理");
                    }
                    if (number(current.get("lock_version")) != input.lockVersion())
                        throw ApiException.conflict("额度申请版本已变化，请刷新");
                    if (approve) {
                        requireEligible(article.resource());
                        if (sql.count("SELECT COUNT(*) FROM sys_users WHERE id=? AND status='ACTIVE'", author) != 1
                                || !sql.has(author, ARTICLE)) throw ApiException.conflict("作者已失去投稿资格");
                        var policy = gate.policy();
                        int limit = input.approvedLimit();
                        if (limit < bodyLimit(resource, policy) || limit > number(current.get("requested_limit"))
                                || limit > policy.limit("maxArticleHardChars"))
                            throw ApiException.invalid("批准额度须在当前有效额度和申请额度之间且不超过硬上限");
                    }
                    sql.db.update("""
                            UPDATE community_quota_requests SET status=?,reviewer_id=?,approved_limit=?,review_note=?,
                                reviewed_at=UTC_TIMESTAMP(3),lock_version=lock_version+1 WHERE id=?
                            """, decision, actor, input.approvedLimit(), note, request);
                    if (approve) sql.db.update("""
                            INSERT INTO community_article_quotas(resource_id,body_char_limit,last_request_id) VALUES(?,?,?) AS incoming
                            ON DUPLICATE KEY UPDATE body_char_limit=incoming.body_char_limit,last_request_id=incoming.last_request_id,
                                lock_version=community_article_quotas.lock_version+1
                            """, resource, input.approvedLimit(), request);
                    sql.audit(actor, "ARTICLE_QUOTA_" + decision, "COMMUNITY_QUOTA", request);
                    sql.db.update("""
                            INSERT INTO user_notifications(user_id,event_key,kind,title,body,resource_id,relative_path)
                            VALUES(?,?,'ARTICLE_QUOTA_RESULT','文章额度审批结果',?,?,?)
                            """, author, "article-quota:" + request, note, resource, "/account/articles/" + resource);
                    return new CommunityWriteGuard.Result("ARTICLE_QUOTA", request, 200);
                }, result -> view(row(result.id(), false)));
    }

    private Map<String, Object> row(long request, boolean lock) {
        return sql.row("SELECT * FROM community_quota_requests WHERE id=?" + (lock ? " FOR UPDATE" : ""), request);
    }

    private static QuotaRequestView view(Map<String, Object> row) {
        return new QuotaRequestView(sid(row.get("id")), sid(row.get("resource_id")), sid(row.get("requested_by")),
                (int) number(row.get("requested_limit")), (String) row.get("reason"), (String) row.get("status"),
                sid(row.get("reviewer_id")), (Integer) row.get("approved_limit"), (String) row.get("review_note"),
                (int) number(row.get("lock_version")), utc(row.get("created_at")), utc(row.get("reviewed_at")));
    }

    private static void requireEligible(Map<String, Object> article) {
        if ("ARCHIVED".equals(article.get("visibility_status"))) throw ApiException.conflict("文章已归档");
    }

    private static String bounded(String value) {
        String text = CommunityTextValidator.lf(value).strip();
        if (text.codePoints().allMatch(CommunityTextValidator::whitespace) || CommunityTextValidator.chars(text) > 2000)
            throw ApiException.invalid("理由或意见不能为空且最多2000字符");
        return text;
    }
}
