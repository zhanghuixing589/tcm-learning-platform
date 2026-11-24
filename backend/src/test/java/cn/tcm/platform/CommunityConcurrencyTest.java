package cn.tcm.platform;

import cn.tcm.platform.content.ContentSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
class CommunityConcurrencyTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ContentSupport sql;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private final List<Long> sections = new ArrayList<>();
    private final List<Long> accounts = new ArrayList<>();
    private final List<Long> roles = new ArrayList<>();

    record Response(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
    }

    private long account(String... roles) {
        long id = sql.insert("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,'test','并发测试')",
                "race_" + UUID.randomUUID().toString().replace("-", ""));
        accounts.add(id);
        for (String role : roles) {
            db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code=?", id, role);
        }
        return id;
    }

    private long section(Long parent) {
        String code = "race-" + UUID.randomUUID();
        long id = sql.insert("INSERT INTO community_sections(parent_id,name,normalized_name,code) VALUES(?,?,?,?)", parent, code, code, code);
        sections.add(id);
        return id;
    }

    private Response call(long actor, MockHttpServletRequestBuilder request) throws Exception {
        var response = perform(actor, request).getResponse();
        return new Response(response.getStatus(), json.readTree(response.getContentAsByteArray()));
    }

    private MvcResult perform(long actor, MockHttpServletRequestBuilder request) throws Exception {
        var authorities = db.queryForList("""
                SELECT DISTINCT p.code FROM sys_permissions p
                JOIN sys_role_permissions rp ON rp.permission_id=p.id JOIN sys_user_roles ur ON ur.role_id=rp.role_id
                WHERE ur.user_id=?
                """, String.class, actor).stream().map(SimpleGrantedAuthority::new).toList();
        return mvc.perform(request.with(user(Long.toString(actor)).authorities(authorities))).andReturn();
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object input) throws Exception {
        return request.contentType("application/json").content(json.writeValueAsBytes(input));
    }

    private MockHttpServletRequestBuilder publication(long section, String key) throws Exception {
        return body(post("/api/v1/posts").header("Idempotency-Key", key),
                Map.of("sectionId", Long.toString(section), "title", "并发讨论", "body", "初次发布"));
    }

    private List<Response> concurrently(Callable<Response> first, Callable<Response> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
                return first.call();
            });
            var two = executor.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
                return second.call();
            });
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(one.get(40, TimeUnit.SECONDS), two.get(40, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void disableAndJoinCannotLeaveADisabledAccountJoined() throws Exception {
        long admin = account("ADMIN");
        long member = account("LEARNER");
        long section = section(null);
        var results = concurrently(
                () -> call(member, put("/api/v1/community/sections/" + section + "/membership")),
                () -> call(admin, body(patch("/api/v1/admin/users/" + member + "/status"), Map.of("status", "DISABLED"))));
        assertThat(results.get(1).status()).isEqualTo(200);
        assertThat(results.get(0).status()).isIn(200, 401);
        assertThat(sql.count("SELECT COUNT(*) FROM community_section_members WHERE user_id=? AND section_id=? AND state='JOINED'", member, section)).isZero();
        long joined = sql.count("SELECT COUNT(*) FROM community_section_events WHERE section_id=? AND action='MEMBER_JOINED'", section);
        long removed = sql.count("SELECT COUNT(*) FROM community_section_events WHERE section_id=? AND action='MEMBER_REMOVED'", section);
        assertThat(removed).isEqualTo(joined);
        assertThat(call(admin, body(patch("/api/v1/admin/users/" + member + "/status"), Map.of("status", "ACTIVE"))).status()).isEqualTo(200);
        assertThat(call(member, get("/api/v1/community/sections/" + section)).data().path("joined").asBoolean()).isFalse();
    }

    @Test
    void sameKeyConcurrentPublicationAndStaleDoubleEditProduceOnlyOneVersionPerWrite() throws Exception {
        long author = account("LEARNER");
        long section = section(null);
        var posts = concurrently(() -> call(author, publication(section, "same-publication")),
                () -> call(author, publication(section, "same-publication")));
        assertThat(posts).extracting(Response::status).containsExactly(201, 201);
        String id = posts.get(0).data().path("id").asText();
        String revision = posts.get(0).data().path("revisionId").asText();
        assertThat(posts.get(1).data().path("id").asText()).isEqualTo(id);
        assertThat(sql.count("SELECT COUNT(*) FROM content_resources WHERE owner_id=?", author)).isEqualTo(1);
        var input = Map.of("baseRevisionId", revision, "lockVersion", 0, "title", "更新标题", "body", "更新内容");
        String path = "/api/v1/posts/" + id + "/revisions";
        var edits = concurrently(
                () -> call(author, body(post(path).header("Idempotency-Key", "editor-tab-one"), input)),
                () -> call(author, body(post(path).header("Idempotency-Key", "editor-tab-two"), input)));
        assertThat(edits).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
        int winner = edits.get(0).status() == 201 ? 0 : 1;
        String key = winner == 0 ? "editor-tab-one" : "editor-tab-two";
        assertThat(call(author, body(post(path).header("Idempotency-Key", key), input)).data().path("revisionId"))
                .isEqualTo(edits.get(winner).data().path("revisionId"));
        assertThat(sql.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?", id)).isEqualTo(2);
        assertThat(sql.count("SELECT COUNT(*) FROM community_revision_profiles WHERE resource_id=?", id)).isEqualTo(2);
        assertThat(sql.count("SELECT COUNT(*) FROM community_write_receipts WHERE actor_id=?", author)).isEqualTo(2);
        assertThat(sql.count("SELECT SUM(attempt_count) FROM community_rate_windows WHERE user_id=?", author)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT body FROM content_revisions WHERE id=?", String.class, revision)).isEqualTo("初次发布");
    }

    @Test
    void sharedLikeEndpointWaitsOnViewerBeforeLockingResource() throws Exception {
        long author = account("LEARNER");
        long viewer = account("LEARNER");
        long section = section(null);
        var published = call(author, publication(section, "like-race-post"));
        assertThat(published.status()).isEqualTo(201);
        String id = published.data().path("id").asText();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var like = new TransactionTemplate(transactions).execute(tx -> {
                db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE", Long.class, viewer);
                var attempt = executor.submit(() -> call(viewer, put("/api/v1/resources/" + id + "/like")));
                awaitSql(attempt, info -> (info.contains("sys_users") && (info.endsWith(viewer + " for share") || info.endsWith(viewer + " for update")))
                        || (info.startsWith("insert into resource_likes") && info.contains("values(" + viewer + "," + id + ")")));
                assertThat(db.queryForObject("SELECT id FROM content_resources WHERE id=? FOR UPDATE NOWAIT", Long.class, id))
                        .isEqualTo(Long.parseLong(id));
                return attempt;
            });
            assertThat(like.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void roleApprovalWaitsOnReviewerBeforeLockingApplicantAndSerializesWithRoleChange() throws Exception {
        long reviewer = account("ADMIN");
        long applicant = account("LEARNER");
        long application = application(applicant);
        var decision = reviewApplication(application);
        var roles = body(put("/api/v1/admin/users/" + applicant + "/roles"), Map.of("roles", List.of("LEARNER", "REVIEWER")));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var approved = new TransactionTemplate(transactions).execute(tx -> {
                db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE", Long.class, reviewer);
                var attempt = executor.submit(() -> call(reviewer, decision));
                awaitSql(attempt, info -> waitingForReviewer(info, reviewer));
                assertThat(db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE NOWAIT", Long.class, applicant))
                        .isEqualTo(applicant);
                assertResponse(reviewer, roles, 200);
                return attempt;
            });
            assertThat(approved.get(20, TimeUnit.SECONDS).data().path("status").asText()).isEqualTo("APPROVED");
            var granted = db.queryForList("""
                    SELECT r.code FROM sys_roles r JOIN sys_user_roles ur ON ur.role_id=r.id WHERE ur.user_id=?
                    """, String.class, applicant);
            assertThat(granted).containsExactlyInAnyOrder("LEARNER", "REVIEWER", "CONTRIBUTOR");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void approvalWaitingForReviewerLockRechecksPermissionAfterRoleRevocation() throws Exception {
        long reviewer = account("ADMIN");
        long administrator = account("ADMIN");
        long applicant = account("LEARNER");
        long application = application(applicant);
        var decision = reviewApplication(application);
        var revoke = body(put("/api/v1/admin/users/" + reviewer + "/roles"), Map.of("roles", List.of("LEARNER")));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var denied = new TransactionTemplate(transactions).execute(tx -> {
                db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE", Long.class, reviewer);
                var attempt = executor.submit(() -> call(reviewer, decision));
                awaitSql(attempt, info -> waitingForReviewer(info, reviewer));
                assertResponse(administrator, revoke, 200);
                return attempt;
            });
            assertThat(denied.get(20, TimeUnit.SECONDS).status()).isEqualTo(403);
            assertThat(db.queryForObject("SELECT status FROM sys_role_applications WHERE id=?", String.class, application))
                    .isEqualTo("PENDING");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void knowledgeBookmarkPreservesResourceFirstWhilePublicationHoldsTarget() throws Exception {
        long owner = account("LEARNER");
        long resource = sql.insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('KNOWLEDGE',?)", owner);
        long revision = sql.insert("""
                INSERT INTO content_revisions(resource_id,version_no,title,body,revision_state,created_by)
                VALUES(?,1,'测试知识','公开知识正文','APPROVED',?)
                """, resource, owner);
        long document = sql.insert("""
                INSERT INTO source_documents(title,source_type,review_state,source_url,submitted_by)
                VALUES('测试来源','WEB','APPROVED','https://example.invalid/stage-task3',?)
                """, owner);
        db.update("INSERT INTO revision_evidence(revision_id,document_id,location_label,quote_text) VALUES(?,?,'章节','证据')", revision, document);
        db.update("UPDATE content_resources SET visibility_status='PUBLISHED',published_revision_id=?,published_at=UTC_TIMESTAMP(3) WHERE id=?", revision, resource);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var bookmarked = new TransactionTemplate(transactions).execute(tx -> {
                db.queryForObject("SELECT id FROM content_resources WHERE id=? FOR UPDATE", Long.class, resource);
                var attempt = executor.submit(() -> call(owner, put("/api/v1/resources/" + resource + "/bookmark")));
                awaitSql(attempt, info -> info.contains("from content_resources where id=" + resource + " for update"));
                assertThat(db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE NOWAIT", Long.class, owner))
                        .isEqualTo(owner);
                return attempt;
            });
            assertThat(bookmarked.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(sql.count("SELECT COUNT(*) FROM resource_bookmarks WHERE user_id=? AND resource_id=?", owner, resource))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void articleClaimAndApprovalWaitForReviewerBeforeTakingAnyDomainLock() throws Exception {
        long reviewer = moderator();
        long author = account("CONTRIBUTOR");
        long section = section(null);
        var draft = call(author, body(post("/api/v1/me/articles").header("Idempotency-Key", "article-review-draft"),
                Map.of("title", "并发审核", "body", "草稿正文", "sectionId", Long.toString(section))));
        assertThat(draft.status()).isEqualTo(201);
        String article = draft.data().path("id").asText();
        String revision = draft.data().path("revisionId").asText();
        var submit = call(author, body(post("/api/v1/me/articles/" + article + "/submit")
                .header("Idempotency-Key", "article-review-submit"), Map.of("baseRevisionId", revision, "lockVersion", 0)));
        assertThat(submit.status()).isEqualTo(200);
        long task = db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?", Long.class, revision);
        String path = "/api/v1/reviews/" + task;
        var executor = Executors.newSingleThreadExecutor();
        try {
            var claim = body(post(path + "/claim"), Map.of("lockVersion", 0));
            var claimed = waitForArticleReviewAccount(executor, reviewer, author, section, article, task, claim, false);
            assertThat(claimed.status()).isEqualTo(200);
            var decision = body(post(path + "/decisions"), Map.of("lockVersion", 1, "decision", "APPROVED", "note", "并发审批"));
            var denied = waitForArticleReviewAccount(executor, reviewer, author, section, article, task, decision, true);
            assertThat(denied.status()).isEqualTo(409);
            assertThat(db.queryForObject("SELECT status FROM review_tasks WHERE id=?", String.class, task)).isEqualTo("PENDING");
            assertThat(db.queryForObject("SELECT visibility_status FROM content_resources WHERE id=?", String.class, article)).isEqualTo("DRAFT");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentQuotaApprovalAndRejectionCommitOnlyOneDecision(boolean approveFirst) throws Exception {
        // Allocate the shared author before the reviewers so both requests acquire that account first.
        long author = account("CONTRIBUTOR"), approver = account("ADMIN"), rejecter = account("ADMIN");
        var draft = call(author, command("/me/articles", "quota-race-draft", Map.of(
                "title", "额度并发", "body", "待扩容正文", "sectionId", Long.toString(section(null)))));
        assertThat(draft.status()).isEqualTo(201);
        String article = draft.data().path("id").asText();
        var request = call(author, command("/me/articles/" + article + "/quota-requests", "quota-race-request",
                Map.of("requestedLimit", 125000, "reason", "需保留全文")));
        assertThat(request.status()).isEqualTo(201);
        String id = request.data().path("id").asText();
        String path = "/admin/community/quota-requests/" + id + "/decisions";
        Callable<Response> approve = () -> call(approver, command(path, "quota-race-approve",
                Map.of("lockVersion", 0, "decision", "APPROVED", "approvedLimit", 120000, "note", "同意扩容")));
        Callable<Response> reject = () -> call(rejecter, command(path, "quota-race-reject",
                Map.of("lockVersion", 0, "decision", "REJECTED", "note", "退回申请")));

        var results = raceBehindArticleLock(author, article, approveFirst ? approve : reject, approveFirst ? reject : approve);
        String expected = approveFirst ? "APPROVED" : "REJECTED";
        long winner = approveFirst ? approver : rejecter;
        assertThat(results.get(0).status()).isEqualTo(200);
        assertThat(results.get(1).status()).isEqualTo(409);
        assertThat(results.get(0).data().path("status").asText()).isEqualTo(expected);
        var decided = db.queryForMap("SELECT status,reviewer_id,lock_version,approved_limit FROM community_quota_requests WHERE id=?", id);
        assertThat(decided).containsEntry("status", expected).containsEntry("reviewer_id", winner)
                .containsEntry("lock_version", 1).containsEntry("approved_limit", approveFirst ? 120000 : null);
        assertThat(db.queryForList("SELECT body_char_limit FROM community_article_quotas WHERE resource_id=?", Integer.class, article))
                .isEqualTo(approveFirst ? List.of(120000) : List.of());
        assertThat(db.queryForList("SELECT body FROM user_notifications WHERE user_id=? AND event_key=?", String.class,
                author, "article-quota:" + id)).containsExactly(approveFirst ? "同意扩容" : "退回申请");
        assertThat(db.queryForList("SELECT action FROM sys_audit_logs WHERE object_type='COMMUNITY_QUOTA' AND object_key=? AND actor_id IN (?,?)",
                String.class, id, approver, rejecter)).containsExactly("ARTICLE_QUOTA_" + expected);
        assertThat(db.queryForList("SELECT actor_id FROM community_write_receipts WHERE operation=?", Long.class,
                "ARTICLE_QUOTA_DECIDE:" + id)).containsExactly(winner);
        assertThat(db.queryForMap("SELECT visibility_status,published_revision_id FROM content_resources WHERE id=?", article))
                .containsEntry("visibility_status", "DRAFT").containsEntry("published_revision_id", null);
    }

    @Test
    void overlappingDraftEditCannotChangeTheVersionThatWonSubmission() throws Exception {
        long author = account("CONTRIBUTOR"), reviewer = moderator();
        long section = section(null);
        String key = UUID.randomUUID().toString();
        var draft = call(author, command("/me/articles", "edit-submit-draft", Map.of("sectionId", Long.toString(section),
                "title", "送审标题", "body", "送审正文[1]。", "references", List.of(reference(key, "送审文献")))));
        assertThat(draft.status()).isEqualTo(201);
        String article = draft.data().path("id").asText();
        String revision = draft.data().path("revisionId").asText();
        String path = "/me/articles/" + article;
        var edited = new java.util.HashMap<String, Object>(articleLock(draft.data()));
        edited.putAll(Map.of("title", "并发编辑标题", "body", "并发编辑正文[1]。", "references", List.of(reference(key, "并发编辑文献"))));

        var results = raceBehindArticleLock(author, article,
                () -> call(author, command(path + "/submit", "edit-submit-command", articleLock(draft.data()))),
                () -> call(author, body(patch("/api/v1" + path), edited)));
        assertThat(results.get(0).status()).isEqualTo(200);
        assertThat(results.get(1).status()).isEqualTo(409);
        var submitted = results.get(0).data();
        assertThat(submitted.path("revisionId").asText()).isEqualTo(revision);
        assertThat(submitted.path("revisionState").asText()).isEqualTo("IN_REVIEW");
        assertThat(submitted.path("lockVersion").asInt()).isEqualTo(draft.data().path("lockVersion").asInt() + 1);
        long task = db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=? AND status='PENDING'", Long.class, revision);
        var review = call(reviewer, get("/api/v1/reviews/" + task));
        assertThat(review.status()).isEqualTo(200);
        var frozen = review.data().path("article");
        for (String field : List.of("title", "body", "sectionId", "sourceFormat", "formatPolicyId", "sourceHash", "references")) {
            assertThat(frozen.path(field)).isEqualTo(draft.data().path("latestRevision").path(field));
        }
        assertThat(sql.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?", article)).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM community_revision_references WHERE revision_id=?", revision)).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM community_write_receipts WHERE actor_id=? AND operation=? AND result_id=?",
                author, "ARTICLE_SUBMIT:" + article, revision)).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM sys_audit_logs WHERE actor_id=? AND action='ARTICLE_SUBMITTED' AND object_key=?",
                author, article)).isEqualTo(1);
        assertThat(db.queryForMap("SELECT visibility_status,published_revision_id FROM content_resources WHERE id=?", article))
                .containsEntry("visibility_status", "DRAFT").containsEntry("published_revision_id", null);
    }

    @Test
    void simultaneousGovernanceHideKeepsPreviousPublicPointerAndBlocksApproval() throws Exception {
        long author = account("CONTRIBUTOR"), reviewer = moderator();
        var draft = call(author, command("/me/articles", "hide-race-draft", Map.of("title", "已公开前版",
                "body", "已公开正文", "sectionId", Long.toString(section(null)))));
        assertThat(draft.status()).isEqualTo(201);
        String article = draft.data().path("id").asText(), path = "/me/articles/" + article;
        var first = call(author, command(path + "/submit", "hide-race-submit-first", articleLock(draft.data())));
        assertThat(first.status()).isEqualTo(200);
        long firstTask = db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?",
                Long.class, first.data().path("revisionId").asText());
        assertThat(call(reviewer, approval(firstTask)).status()).isEqualTo(200);
        var publicVersion = call(author, get("/api/v1" + path)).data();
        var next = call(author, command(path + "/revise", "hide-race-revise", articleLock(publicVersion)));
        assertThat(next.status()).isEqualTo(200);
        var candidate = call(author, command(path + "/submit", "hide-race-submit-next", articleLock(next.data())));
        assertThat(candidate.status()).isEqualTo(200);
        String revision = candidate.data().path("revisionId").asText();
        long task = db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?", Long.class, revision);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pendingApproval = new TransactionTemplate(transactions).execute(tx -> {
                // Commit the resource update that represents governance hiding while the real approval request is blocked.
                db.update("UPDATE content_resources SET visibility_status='SUSPENDED',suspended_reason='治理下架' WHERE id=?", article);
                var attempt = executor.submit(() -> call(reviewer, approval(task)));
                awaitSql(attempt, info -> waitingForArticle(info, article));
                return attempt;
            });
            assertThat(pendingApproval.get(20, TimeUnit.SECONDS).status()).isEqualTo(409);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        var resource = db.queryForMap("SELECT visibility_status,published_revision_id,suspended_reason FROM content_resources WHERE id=?", article);
        assertThat(resource).containsEntry("visibility_status", "SUSPENDED").containsEntry("suspended_reason", "治理下架")
                .containsEntry("published_revision_id", publicVersion.path("publishedRevisionId").asLong());
        assertThat(db.queryForMap("SELECT status,lock_version,reviewer_id FROM review_tasks WHERE id=?", task))
                .containsEntry("status", "PENDING").containsEntry("lock_version", 0).containsEntry("reviewer_id", null);
        assertThat(db.queryForMap("SELECT revision_state,approved_at FROM content_revisions WHERE id=?", revision))
                .containsEntry("revision_state", "IN_REVIEW").containsEntry("approved_at", null);
        assertThat(db.queryForObject("SELECT release_method FROM community_revision_profiles WHERE revision_id=?", String.class, revision))
                .isEqualTo("NONE");
        assertThat(sql.count("SELECT COUNT(*) FROM review_events WHERE review_task_id=?", task)).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM user_notifications WHERE user_id=? AND event_key=?", author, "review:" + task)).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM sys_audit_logs WHERE actor_id=? AND action='REVIEW_APPROVED' AND object_key=?", reviewer, task))
                .isZero();
    }

    @Test
    void retryAfterInnoDbDeadlockPublishesAndChargesOnlyOnce() throws Exception {
        long author = account("LEARNER"), lockOwner = account("LEARNER");
        long section = section(null);
        String key = "publication-deadlock-retry";
        for (int i = 1; i <= 20; i++) {
            db.update("""
                    INSERT INTO community_rate_windows(user_id,operation,window_seconds,window_start,attempt_count,policy_id)
                    SELECT ?,'DEADLOCK_TEST',?,'2026-01-01 00:00:00',0,policy_id FROM community_text_policy_heads WHERE id=1
                    """, lockOwner, i);
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            var publication = new TransactionTemplate(transactions).execute(tx -> {
                db.queryForObject("SELECT id FROM community_sections WHERE id=? FOR UPDATE", Long.class, section);
                // Make the holder heavier than a transaction with a single new receipt so InnoDB selects the API request.
                assertThat(db.update("UPDATE community_rate_windows SET attempt_count=1 WHERE user_id=?", lockOwner)).isEqualTo(20);
                var attempt = executor.submit(() -> perform(author, publication(section, key)));
                awaitSql(attempt, info -> info.startsWith("select * from community_sections where id=" + section + " for update"));
                // The request has reserved the receipt and holds the author lock while it waits for the section.
                assertThat(db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE", Long.class, author)).isEqualTo(author);
                return attempt;
            });
            var failed = publication.get(20, TimeUnit.SECONDS);
            assertThat(failed.getResponse().getStatus()).isEqualTo(500);
            assertThat(failed.getResolvedException()).hasRootCauseInstanceOf(SQLException.class);
            assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failed.getResolvedException()))
                    .isInstanceOfSatisfying(SQLException.class, cause -> assertThat(cause.getErrorCode()).isEqualTo(1213));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(sql.count("SELECT COUNT(*) FROM community_write_receipts WHERE actor_id=? AND request_key=?", author, key)).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM content_resources WHERE owner_id=?", author)).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM community_rate_windows WHERE user_id=?", author)).isZero();

        var retried = call(author, publication(section, key));
        assertThat(retried.status()).isEqualTo(201);
        assertThat(call(author, publication(section, key)).data()).isEqualTo(retried.data());
        String id = retried.data().path("id").asText();
        assertThat(sql.count("SELECT COUNT(*) FROM content_resources WHERE owner_id=? AND visibility_status='PUBLISHED'", author)).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=? AND version_no=1", id)).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?", id)).isEqualTo(1);
        assertThat(db.queryForMap("SELECT result_kind,result_id,http_status FROM community_write_receipts WHERE actor_id=? AND request_key=?", author, key))
                .containsEntry("result_kind", "POST").containsEntry("result_id", Long.parseLong(id)).containsEntry("http_status", 201);
        assertThat(db.queryForList("SELECT attempt_count FROM community_rate_windows WHERE user_id=? AND operation='POST_CREATE'", Integer.class, author))
                .containsExactly(1, 1);
        assertThat(sql.count("SELECT COUNT(*) FROM sys_audit_logs WHERE actor_id=? AND action='POST_PUBLISHED' AND object_key=?", author, id)).isEqualTo(1);
    }

    private List<Response> raceBehindArticleLock(long author, String article, Callable<Response> first,
                                                  Callable<Response> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var attempts = new TransactionTemplate(transactions).execute(tx -> {
                db.queryForObject("SELECT id FROM content_resources WHERE id=? FOR UPDATE", Long.class, article);
                var one = executor.submit(first);
                awaitSql(one, info -> waitingForArticle(info, article));
                var two = executor.submit(second);
                awaitSql(two, info -> info.startsWith("select status from sys_users where id=" + author + " for update"));
                return List.of(one, two);
            });
            return List.of(attempts.get(0).get(20, TimeUnit.SECONDS), attempts.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static boolean waitingForArticle(String info, String article) {
        return info.contains("from content_resources where id=" + article + " and owner_id=") && info.endsWith("for update");
    }

    private MockHttpServletRequestBuilder command(String path, String key, Object input) throws Exception {
        return body(post("/api/v1" + path).header("Idempotency-Key", key), input);
    }

    private MockHttpServletRequestBuilder approval(long task) throws Exception {
        return body(post("/api/v1/reviews/" + task + "/decisions"),
                Map.of("lockVersion", 0, "decision", "APPROVED", "note", "并发审批"));
    }

    private Map<String, Object> articleLock(JsonNode article) {
        return Map.of("baseRevisionId", article.path("revisionId").asText(), "lockVersion", article.path("lockVersion").asInt());
    }

    private Map<String, Object> reference(String key, String title) {
        return Map.of("referenceKey", key, "displayNumber", 1, "title", title);
    }

    private long moderator() {
        long reviewer = account("LEARNER");
        long role = sql.insert("INSERT INTO sys_roles(code,name) VALUES(?,'文章审核测试')",
                "RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        roles.add(role);
        db.update("INSERT INTO sys_role_permissions(role_id,permission_id) SELECT ?,id FROM sys_permissions WHERE code='community:moderate'", role);
        db.update("INSERT INTO sys_user_roles(user_id,role_id) VALUES(?,?)", reviewer, role);
        return reviewer;
    }

    private Response waitForArticleReviewAccount(java.util.concurrent.ExecutorService executor, long reviewer, long author,
                                                  long section, String resource, long task,
                                                  MockHttpServletRequestBuilder command, boolean revoke) throws Exception {
        var result = new TransactionTemplate(transactions).execute(tx -> {
            db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE", Long.class, reviewer);
            var attempt = executor.submit(() -> call(reviewer, command));
            awaitSql(attempt, info -> info.startsWith("select status from sys_users where id=" + reviewer + " for update"));
            assertThat(db.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE NOWAIT", Long.class, author)).isEqualTo(author);
            assertThat(db.queryForObject("SELECT id FROM community_sections WHERE id=? FOR UPDATE NOWAIT", Long.class, section)).isEqualTo(section);
            assertThat(db.queryForObject("SELECT id FROM content_resources WHERE id=? FOR UPDATE NOWAIT", String.class, resource)).isEqualTo(resource);
            assertThat(db.queryForObject("SELECT id FROM review_tasks WHERE id=? FOR UPDATE NOWAIT", Long.class, task)).isEqualTo(task);
            if (revoke) db.update("DELETE FROM sys_user_roles WHERE user_id=?", author);
            return attempt;
        });
        return result.get(20, TimeUnit.SECONDS);
    }

    @Test
    void notificationFailureRollsBackQuotaDecisionAuditLimitAndReceiptTogether() throws Exception {
        long admin = account("ADMIN");
        long author = account("CONTRIBUTOR");
        long section = section(null);
        var draft = call(author, body(post("/api/v1/me/articles").header("Idempotency-Key", "quota-rollback-draft"),
                Map.of("title", "额度回滚", "body", "草稿正文", "sectionId", Long.toString(section))));
        assertThat(draft.status()).isEqualTo(201);
        String article = draft.data().path("id").asText();
        var request = call(author, body(post("/api/v1/me/articles/" + article + "/quota-requests")
                .header("Idempotency-Key", "quota-rollback-request"), Map.of("requestedLimit", 125000, "reason", "申请额度")));
        assertThat(request.status()).isEqualTo(201);
        String id = request.data().path("id").asText(), event = "article-quota:" + id;
        db.update("INSERT INTO user_notifications(user_id,event_key,kind,title,body,resource_id) VALUES(?,?,'ARTICLE_QUOTA_RESULT','冲突占位','测试',?)",
                author, event, article);
        String decision = "/api/v1/admin/community/quota-requests/" + id + "/decisions";
        var payload = Map.of("lockVersion", 0, "decision", "APPROVED", "approvedLimit", 120000, "note", "确认");
        assertThat(call(admin, body(post(decision).header("Idempotency-Key", "quota-rollback-decision"), payload)).status()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT status FROM community_quota_requests WHERE id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(sql.count("SELECT COUNT(*) FROM community_article_quotas WHERE resource_id=?", article)).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM sys_audit_logs WHERE actor_id=? AND action='ARTICLE_QUOTA_APPROVED'", admin)).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM community_write_receipts WHERE actor_id=?", admin)).isZero();
        db.update("DELETE FROM user_notifications WHERE user_id=? AND event_key=?", author, event);
        assertThat(call(admin, body(post(decision).header("Idempotency-Key", "quota-rollback-decision"), payload)).status()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT body_char_limit FROM community_article_quotas WHERE resource_id=?", Integer.class, article))
                .isEqualTo(120000);
    }

    @Test
    void twoAuthorsCannotAssignTheSameNewReferenceKey() throws Exception {
        long one = account("CONTRIBUTOR"), two = account("CONTRIBUTOR");
        long firstSection = section(null), secondSection = section(null);
        String key = UUID.randomUUID().toString();
        var ref = List.of(Map.of("referenceKey", key, "displayNumber", 1, "title", "共享 UUID"));
        MockHttpServletRequestBuilder first = body(post("/api/v1/me/articles").header("Idempotency-Key", "first-reference"),
                Map.of("title", "第一篇", "body", "[1]", "sectionId", Long.toString(firstSection), "references", ref));
        MockHttpServletRequestBuilder second = body(post("/api/v1/me/articles").header("Idempotency-Key", "second-reference"),
                Map.of("title", "第二篇", "body", "[1]", "sectionId", Long.toString(secondSection), "references", ref));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var requests = new TransactionTemplate(transactions).execute(tx -> {
                db.queryForObject("SELECT id FROM community_text_policy_heads WHERE id=1 FOR UPDATE", Long.class);
                var a = executor.submit(() -> call(one, first));
                var b = executor.submit(() -> call(two, second));
                // The authors and sections are disjoint. The shared lock serializes only UUID assignment.
                awaitSql(a, info -> info.contains("from community_text_policy_heads") && info.endsWith("for update"));
                awaitSql(b, info -> info.contains("from community_text_policy_heads") && info.endsWith("for update"));
                return List.of(a, b);
            });
            var firstResult = requests.get(0).get(20, TimeUnit.SECONDS);
            var secondResult = requests.get(1).get(20, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.status(), secondResult.status())).containsExactlyInAnyOrder(201, 400);
            assertThat(sql.count("SELECT COUNT(*) FROM community_revision_references WHERE reference_key=?", key)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long application(long applicant) {
        return sql.insert("""
                INSERT INTO sys_role_applications(user_id,role_id,statement)
                SELECT ?,id,'申请贡献资格' FROM sys_roles WHERE code='CONTRIBUTOR'
                """, applicant);
    }

    private MockHttpServletRequestBuilder reviewApplication(long application) throws Exception {
        return body(patch("/api/v1/admin/role-applications/" + application),
                Map.of("decision", "APPROVED", "note", "通过贡献资格申请"));
    }

    private void assertResponse(long actor, MockHttpServletRequestBuilder request, int status) {
        try {
            assertThat(call(actor, request).status()).isEqualTo(status);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean waitingForReviewer(String info, long reviewer) {
        return info.startsWith("select * from sys_users where id=" + reviewer + " for update")
                || (info.startsWith("update sys_role_applications set") && info.contains("reviewer_id=" + reviewer + ","));
    }

    private void awaitSql(Future<?> attempt, Predicate<String> statement) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline && !attempt.isDone()) {
            boolean blocked = db.query("SHOW FULL PROCESSLIST", (rs, n) -> rs.getString("Info")).stream()
                    .filter(java.util.Objects::nonNull).map(info -> info.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                    .anyMatch(statement);
            if (blocked) return;
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        throw new AssertionError("Competing request did not reach the expected lock statement");
    }

    @Test
    void publicationRacingWithParentDeactivationRemainsAtomicAndHistoryReadable() throws Exception {
        long admin = account("ADMIN");
        long author = account("LEARNER");
        long parent = section(null);
        long child = section(parent);
        var previous = call(author, publication(child, "preexisting-publication"));
        assertThat(previous.status()).isEqualTo(201);
        String existingId = previous.data().path("id").asText();
        var results = concurrently(
                () -> call(author, publication(child, "racing-publication")),
                () -> call(admin, body(patch("/api/v1/admin/community/sections/" + parent), Map.of("lockVersion", 0, "active", false))));
        assertThat(results.get(1).status()).isEqualTo(200);
        assertThat(results.get(0).status()).isIn(201, 409);
        assertThat(call(author, publication(child, "blocked-publication")).status()).isEqualTo(409);
        assertThat(call(author, get("/api/v1/community/sections/" + child)).data().path("writable").asBoolean()).isFalse();
        assertThat(call(author, get("/api/v1/posts/" + existingId)).status()).isEqualTo(200);
        assertThat(call(author, get("/api/v1/resources/" + existingId)).status()).isEqualTo(200);
        int total = results.get(0).status() == 201 ? 2 : 1;
        assertThat(sql.count("SELECT COUNT(*) FROM content_resources WHERE owner_id=?", author)).isEqualTo(total);
        assertThat(sql.count("SELECT COUNT(*) FROM community_write_receipts WHERE actor_id=?", author)).isEqualTo(total);
        assertThat(sql.count("""
                SELECT COUNT(*) FROM content_resources r JOIN community_post_profiles cp ON cp.resource_id=r.id
                JOIN community_revision_profiles rp ON rp.revision_id=r.published_revision_id
                WHERE r.owner_id=? AND r.visibility_status='PUBLISHED' AND cp.section_id=? AND rp.section_id=?
                """, author, child, child)).isEqualTo(total);
    }

    @AfterEach
    void cleanup() {
        for (long account : accounts) {
            db.update("DELETE FROM user_resource_history WHERE user_id=?", account);
            db.update("DELETE FROM resource_likes WHERE user_id=?", account);
            db.update("DELETE FROM resource_bookmarks WHERE user_id=?", account);
            db.update("DELETE FROM sys_role_applications WHERE user_id=? OR reviewer_id=?", account, account);
        }
        for (long account : accounts) {
            for (long post : db.queryForList("SELECT id FROM content_resources WHERE owner_id=?", Long.class, account)) {
                db.update("UPDATE content_resources SET visibility_status='DRAFT',published_revision_id=NULL WHERE id=?", post);
                db.update("DELETE FROM user_notifications WHERE resource_id=?", post);
                db.update("DELETE FROM community_article_quotas WHERE resource_id=?", post);
                db.update("DELETE FROM community_quota_requests WHERE resource_id=?", post);
                db.update("DELETE FROM community_revision_profiles WHERE resource_id=?", post);
                db.update("DELETE FROM community_post_profiles WHERE resource_id=?", post);
                for (long version : db.queryForList("SELECT id FROM content_revisions WHERE resource_id=? ORDER BY version_no DESC", Long.class, post)) {
                    db.update("DELETE e FROM review_events e JOIN review_tasks t ON t.id=e.review_task_id WHERE t.revision_id=?", version);
                    db.update("DELETE FROM review_tasks WHERE revision_id=?", version);
                    db.update("DELETE FROM community_revision_references WHERE revision_id=?", version);
                    db.update("DELETE FROM revision_evidence WHERE revision_id=?", version);
                    db.update("DELETE FROM content_revisions WHERE id=?", version);
                }
                db.update("DELETE FROM content_resources WHERE id=?", post);
            }
            db.update("DELETE FROM source_documents WHERE submitted_by=?", account);
        }
        for (int i = sections.size() - 1; i >= 0; i--) {
            long section = sections.get(i);
            db.update("DELETE FROM community_section_events WHERE section_id=?", section);
            db.update("DELETE FROM community_section_members WHERE section_id=?", section);
            db.update("DELETE FROM community_sections WHERE id=?", section);
        }
        for (long account : accounts) {
            db.update("DELETE FROM sys_audit_logs WHERE actor_id=?", account);
            db.update("DELETE FROM community_rate_windows WHERE user_id=?", account);
            db.update("DELETE FROM community_write_receipts WHERE actor_id=?", account);
            db.update("DELETE FROM sys_user_roles WHERE user_id=?", account);
        }
        for (long account : accounts) db.update("DELETE FROM sys_users WHERE id=?", account);
        for (long role : roles) {
            db.update("DELETE FROM sys_role_permissions WHERE role_id=?", role);
            db.update("DELETE FROM sys_roles WHERE id=?", role);
        }
    }
}
