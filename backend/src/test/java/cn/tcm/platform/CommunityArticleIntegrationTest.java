package cn.tcm.platform;

import cn.tcm.platform.community.application.CommunitySourceHash;
import cn.tcm.platform.content.ContentSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommunityArticleIntegrationTest extends CommunityArticleFixtures {
    @Test
    void activePolicyCanAllowMoreThanLegacyDtoLimit() throws Exception {
        long author = account("CONTRIBUTOR");
        db.update("""
                UPDATE community_text_policies p JOIN community_text_policy_heads h ON h.policy_id=p.id
                SET p.config=JSON_SET(p.config,'$.maxArticleChars',110000)
                """);
        String source = "中".repeat(100001) + "\r\n  ";
        var draft = call(author, command("/me/articles", Map.of("title", "长文", "body", source)), 201);
        assertThat(draft.path("body").asText()).isEqualTo(source.replace("\r\n", "\n"));
    }

    @Test
    void articleReferencesSurviveDraftSaveAndFreezeOnSubmission() throws Exception {
        long author = account("CONTRIBUTOR");
        long reviewer = moderator();
        String key = UUID.randomUUID().toString();
        String section = section();
        var draft = call(author, command("/me/articles", Map.of("title", "阅读笔记", "sectionId", section,
                "body", "原文[1]。", "references", List.of(reference(key, 1, "正式文献")))), 201);
        String path = "/me/articles/" + draft.path("id").asText();
        assertThat(draft.path("latestRevision").path("references").get(0)).isNotNull();
        call(author, body(patch(API + path), Map.of("title", "无锁", "body", "无锁")), 400);
        var submitted = call(author, command(path + "/submit", lock(draft)), 200);
        assertThat(releaseMethod(submitted)).isEqualTo("NONE");
        String task = task(submitted);
        var frozen = call(reviewer, get(API + "/reviews/" + task), 200).path("article");
        assertThat(frozen.path("references").get(0).path("title").asText()).isEqualTo("正式文献");
        call(reviewer, body(post(API + "/reviews/" + task + "/decisions"),
                Map.of("lockVersion", 0, "decision", "APPROVED", "note", "通过")), 200);
        var approved = call(author, get(API + path), 200);
        assertThat(releaseMethod(approved)).isEqualTo("MANUAL_REVIEW");
        var candidate = call(author, command(path + "/revise", lock(approved)), 200);
        var patch = new java.util.HashMap<String, Object>(lock(candidate));
        patch.putAll(Map.of("title", "候选标题", "body", "候选内容[1]。", "references", List.of(reference(key, 1, "候选文献"))));
        call(author, body(patch(API + path), patch), 200);
        var published = call(reviewer, get(API + "/resources/" + draft.path("id").asText()), 200);
        assertThat(published.path("body").asText()).isEqualTo("原文[1]。");
        assertThat(published.path("community").path("body").isNull()).isTrue();
        assertThat(published.path("community").path("references")).isEqualTo(published.path("references"));
        assertThat(published.path("section").path("id").asText()).isEqualTo(section);
        assertThat(published.path("references").get(0).path("title").asText()).isEqualTo("正式文献");
        assertThat(call(reviewer, get(API + "/reviews/" + task), 200).path("article")
                .path("references").get(0).path("title").asText()).isEqualTo("正式文献");
    }

    @Test
    void ordinarySavePreservesReferencesAndExplicitReorderRewritesOnlyCitations() throws Exception {
        long author = account("CONTRIBUTOR");
        String one = UUID.randomUUID().toString(), two = UUID.randomUUID().toString();
        String source = "文献 [1]，[2]，`[1]`，[1](https://example.org) 和 \\[1]。\n\n```\n[2]\n```\n";
        var refs = List.of(reference(one, 1, "文献一"), reference(two, 2, "文献二"));
        var draft = call(author, command("/me/articles", Map.of("title", "文献重排", "sectionId", section(),
                "body", source, "sourceFormat", "MARKDOWN", "references", refs)), 201);
        String path = "/me/articles/" + draft.path("id").asText();
        var saved = call(author, body(patch(API + path), update(draft, "修订标题", source)), 200);
        assertThat(saved.path("references").size()).isEqualTo(2);
        var mutation = update(saved, "暗改编号", source);
        mutation.put("references", List.of(reference(one, 2, "文献一"), reference(two, 1, "文献二")));
        call(author, body(patch(API + path), mutation), 400);
        mutation.put("references", List.of(reference(UUID.randomUUID().toString(), 1, "替代文献"), refs.get(1)));
        call(author, body(patch(API + path), mutation), 400);
        mutation.put("references", List.of());
        call(author, body(patch(API + path), mutation), 400);
        mutation.putAll(lock(draft));
        call(author, body(patch(API + path), mutation), 409);

        var order = new java.util.HashMap<String, Object>(lock(saved));
        order.put("referenceKeys", List.of(two));
        call(author, command(path + "/references/reorder", order), 400);
        order.put("referenceKeys", List.of(two, one));
        var reordered = call(author, command(path + "/references/reorder", order, "test-reorder-1"), 200);
        assertThat(reordered.path("body").asText()).isEqualTo(source.replace("文献 [1]，[2]", "文献 [2]，[1]"));
        assertThat(reordered.path("references").get(0).path("referenceKey").asText()).isEqualTo(two);
        assertThat(reordered.path("lockVersion").asInt()).isEqualTo(saved.path("lockVersion").asInt() + 1);
        assertThat(call(author, command(path + "/references/reorder", order, "test-reorder-1"), 200)).isEqualTo(reordered);

        var clear = update(reordered, "移除引文", "仅正文，不引用文献。");
        clear.put("references", List.of());
        var cleared = call(author, body(patch(API + path), clear), 200);
        assertThat(cleared.path("references").size()).isZero();
    }

    @Test
    void reorderChecksQuotaOfRewrittenBodyBeforePersisting() throws Exception {
        long author = account("CONTRIBUTOR");
        var refs = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(number -> reference(UUID.randomUUID().toString(), number, "文献" + number)).toList();
        var draft = call(author, command("/me/articles", Map.of("title", "重排后超限", "body", "[1]", "references", refs)), 201);
        String path = "/me/articles/" + draft.path("id").asText();
        db.update("""
                UPDATE community_text_policies p JOIN community_text_policy_heads h ON h.policy_id=p.id
                SET p.config=JSON_SET(p.config,'$.maxArticleChars',3)
                """);
        var order = new java.util.HashMap<String, Object>(lock(draft));
        var keys = new java.util.ArrayList<>(refs.stream().map(ref -> ref.get("referenceKey")).toList());
        java.util.Collections.rotate(keys, -1);
        order.put("referenceKeys", keys);
        call(author, command(path + "/references/reorder", order), 400);
        var current = call(author, get(API + path), 200);
        assertThat(current.path("body").asText()).isEqualTo("[1]");
        assertThat(current.path("references")).isEqualTo(draft.path("references"));
        assertThat(current.path("lockVersion")).isEqualTo(draft.path("lockVersion"));
    }

    @Test
    void legacyTitleAndSignificantWhitespaceSurviveRevisionAndLegacyDraftCanBeDeleted() throws Exception {
        long author = account("CONTRIBUTOR");
        String title = " " + "长".repeat(121) + " ";
        String source = "  保留空白  \r\n\r\n";
        String section = section();
        long id = sql.insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('ARTICLE',?)", author);
        sql.insert("""
                INSERT INTO content_revisions(resource_id,version_no,title,body,revision_state,created_by)
                VALUES(?,1,?,?,'WITHDRAWN',?)
                """, id, title, source, author);
        String path = "/me/articles/" + id;
        var legacy = call(author, get(API + path), 200);
        var revised = call(author, command(path + "/revise", lock(legacy)), 200);
        assertThat(revised.path("title").asText()).isEqualTo(title);
        assertThat(revised.path("body").asText()).isEqualTo(source.replace("\r\n", "\n"));
        var input = update(revised, title, source);
        input.put("sectionId", section);
        var saved = call(author, body(patch(API + path), input), 200);
        assertThat(saved.path("title").asText()).isEqualTo(title);
        input.putAll(lock(saved));
        input.put("title", title + "改");
        call(author, body(patch(API + path), input), 400);
        var draft = call(author, command("/me/articles", Map.of("title", "删除草稿", "body", "删除前正文"), "delete-replay-1"), 201);
        String draftPath = "/me/articles/" + draft.path("id").asText();
        call(author, delete(API + draftPath).param("baseRevisionId", draft.path("revisionId").asText())
                .param("lockVersion", draft.path("lockVersion").asText()), 200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_resources WHERE id=?", Long.class,
                draft.path("id").asText())).isZero();
        call(author, command("/me/articles", Map.of("title", "删除草稿", "body", "删除前正文"), "delete-replay-1"), 404);
    }

    @Test
    void legacySectionBootstrapDoesNotAdoptCollationEquivalentUserSection() throws Exception {
        long author = account("CONTRIBUTOR");
        long existing = sql.count("SELECT COUNT(*) FROM community_sections WHERE code='legacy-content'");
        if (existing != 0) {
            // A migration-created container is valid and cannot share its reserved key with a user section.
            var draft = call(author, command("/me/articles", Map.of("title", "历史容器", "body", "正文")), 201);
            assertThat(db.queryForObject("SELECT code FROM community_sections WHERE id=?", String.class,
                    draft.path("sectionId").asText())).isEqualTo("legacy-content");
            return;
        }
        long conflict = sql.insert("""
                INSERT INTO community_sections(name,normalized_name,code,created_by,active)
                VALUES('用户分区','用户分区','LEGACY-CONTENT',?,1)
                """, author);
        call(author, command("/me/articles", Map.of("title", "历史容器", "body", "正文")), 409);
        assertThat(db.queryForObject("SELECT code FROM community_sections WHERE id=?", String.class, conflict)).isEqualTo("LEGACY-CONTENT");
        assertThat(sql.count("SELECT COUNT(*) FROM content_resources WHERE owner_id=?", author)).isZero();
    }

    @Test
    void danglingReferencesCanBeSavedButReadonlyOrUnresolvedDraftCannotSubmit() throws Exception {
        long author = account("CONTRIBUTOR");
        String section = section();
        var draft = call(author, command("/me/articles", Map.of("title", "缺少文献", "body", "正文[1]", "sectionId", section)), 201);
        assertThat(draft.path("danglingReferenceNumbers").get(0).asInt()).isEqualTo(1);
        String path = "/me/articles/" + draft.path("id").asText();
        call(author, command(path + "/submit", lock(draft)), 400);
        var input = update(draft, "已补齐文献", "正文[1]");
        input.put("references", List.of(reference(UUID.randomUUID().toString(), 1, "已补齐的文献")));
        var saved = call(author, body(patch(API + path), input), 200);
        db.update("UPDATE community_sections SET active=0 WHERE id=?", section);
        call(author, command(path + "/submit", lock(saved)), 409);
        assertThat(call(author, get(API + path), 200).path("revisionState").asText()).isEqualTo("DRAFT");
    }

    @Test
    void lifecycleReceiptsReplayTheirOriginalRevisionEvenAfterLaterVersions() throws Exception {
        long author = account("CONTRIBUTOR"), reviewer = moderator();
        String ref = UUID.randomUUID().toString();
        var first = call(author, command("/me/articles", Map.of("title", "原始版本", "body", "原始正文[1]",
                "sectionId", section(), "references", List.of(reference(ref, 1, "初始文献")))), 201);
        String path = "/me/articles/" + first.path("id").asText();
        var submitted = call(author, command(path + "/submit", lock(first), "submit-original"), 200);
        call(reviewer, body(post(API + "/reviews/" + task(submitted) + "/decisions"),
                Map.of("lockVersion", 0, "decision", "APPROVED", "note", "通过")), 200);
        var approved = call(author, get(API + path), 200);
        var second = call(author, command(path + "/revise", lock(approved), "revise-second"), 200);
        var reorder = new java.util.HashMap<String, Object>(lock(second));
        reorder.put("referenceKeys", List.of(ref));
        var reordered = call(author, command(path + "/references/reorder", reorder, "reorder-second"), 200);
        var submittedSecond = call(author, command(path + "/submit", lock(reordered), "submit-second"), 200);
        var withdrawn = call(author, command(path + "/withdraw", lock(submittedSecond), "withdraw-second"), 200);
        assertThat(releaseMethod(withdrawn)).isEqualTo("NONE");
        var third = call(author, command(path + "/revise", lock(withdrawn), "revise-third"), 200);
        var current = call(author, body(patch(API + path), update(third, "第三版标题", "第三版正文[1]")), 200);
        var replays = List.of(
                Map.entry(command(path + "/submit", lock(first), "submit-original"), first),
                Map.entry(command(path + "/revise", lock(approved), "revise-second"), second),
                Map.entry(command(path + "/references/reorder", reorder, "reorder-second"), second),
                Map.entry(command(path + "/submit", lock(reordered), "submit-second"), second),
                Map.entry(command(path + "/withdraw", lock(submittedSecond), "withdraw-second"), second));
        for (var entry : replays) {
            var replay = call(author, entry.getKey(), 200);
            String expected = entry.getValue().path("revisionId").asText();
            assertThat(replay.path("revisionId").asText()).isEqualTo(expected);
            assertThat(replay.path("resultRevision").path("revisionId").asText()).isEqualTo(expected);
            assertThat(replay.path("latestRevision").path("revisionId")).isEqualTo(current.path("revisionId"));
            assertThat(replay.path("staleResult").asBoolean()).isTrue();
            assertThat(replay.path("capabilities").path("canEdit").asBoolean()).isFalse();
            assertThat(replay.path("body").asText()).isEqualTo("原始正文[1]");
        }
    }

    @Test
    void reservedLegacyNameCannotBeTakenThroughSectionManagement() throws Exception {
        long author = account("CONTRIBUTOR"), admin = account("ADMIN");
        call(admin, command("/admin/community/sections",
                Map.of("code", "pretend-legacy", "name", "　历史内容　", "sortOrder", 4)), 400);
        var draft = call(author, command("/me/articles/draft-identities", Map.of("title", "待申请额度")), 201);
        assertThat(db.queryForObject("SELECT code FROM community_sections WHERE id=?", String.class,
                draft.path("sectionId").asText())).isEqualTo("legacy-content");
    }

    @Test
    void markdownReorderThatChangesCitationMeaningLeavesDraftAndReferencesIntact() throws Exception {
        long author = account("CONTRIBUTOR");
        String one = UUID.randomUUID().toString(), two = UUID.randomUUID().toString();
        String source = "参见 [1]\n\n[2]: https://example.com";
        var draft = call(author, command("/me/articles", Map.of("title", "快捷链接冲突", "body", source,
                "sourceFormat", "MARKDOWN", "sectionId", section(),
                "references", List.of(reference(one, 1, "文献一"), reference(two, 2, "文献二")))), 201);
        String path = "/me/articles/" + draft.path("id").asText();
        var reorder = new java.util.HashMap<String, Object>(lock(draft));
        reorder.put("referenceKeys", List.of(two, one));
        call(author, command(path + "/references/reorder", reorder), 400);
        var saved = call(author, get(API + path), 200);
        assertThat(saved.path("latestRevision")).isEqualTo(draft.path("latestRevision"));
        assertThat(saved.path("lockVersion")).isEqualTo(draft.path("lockVersion"));
    }

    @Test
    void migratedPublicArticleCanBeClassifiedOnceAtApproval() throws Exception {
        long author = account("CONTRIBUTOR"), moderator = moderator(), reader = account("LEARNER");
        String legacy = legacySection();
        String formal = section();
        String other = newSection("FORMAL");
        long article = migratedPublishedArticle(author, legacy, "LEGACY");
        String path = "/me/articles/" + article;
        String publicPath = API + "/resources/" + article;

        var oldPublic = call(reader, get(publicPath), 200);
        assertThat(oldPublic.path("section").path("id").asText()).isEqualTo(legacy);
        assertThat(oldPublic.path("body").asText()).isEqualTo("  原公开正文 **纯文本**  ");
        String firstPublishedAt = oldPublic.path("community").path("publishedAt").asText();
        assertThat(firstPublishedAt).isNotBlank();

        var current = call(author, get(API + path), 200);
        var draft = call(author, command(path + "/revise", lock(current)), 200);
        var input = update(draft, "首次归类", "归类后的正文[1]");
        input.put("references", List.of(reference(UUID.randomUUID().toString(), 1, "新增候选文献")));
        input.put("sectionId", newSection("OBSERVING"));
        call(author, body(patch(API + path), input), 409);
        input.put("sectionId", formal);
        var saved = call(author, body(patch(API + path), input), 200);
        assertThat(saved.path("latestRevision").path("sectionId").asText()).isEqualTo(formal);
        assertThat(saved.path("published").path("section").path("id").asText()).isEqualTo(legacy);
        var submitted = call(author, command(path + "/submit", lock(saved)), 200);
        var stillPublic = call(reader, get(publicPath), 200);
        for (String field : List.of("revisionId", "title", "body", "section", "references")) {
            assertThat(stillPublic.path(field)).isEqualTo(oldPublic.path(field));
        }
        String task = task(submitted);
        assertThat(call(moderator, get(API + "/reviews/" + task), 200).path("article").path("sectionId").asText())
                .isEqualTo(formal);
        call(moderator, body(post(API + "/reviews/" + task + "/decisions"),
                Map.of("lockVersion", 0, "decision", "REJECTED", "note", "补充引文细节")), 200);
        assertThat(releaseMethod(submitted)).isEqualTo("NONE");
        assertThat(releaseMethod(oldPublic)).isEqualTo("LEGACY");
        var afterRejection = call(reader, get(publicPath), 200);
        for (String field : List.of("revisionId", "title", "body", "section", "references")) {
            assertThat(afterRejection.path(field)).isEqualTo(oldPublic.path(field));
        }

        var retryDraft = call(author, command(path + "/revise", lock(call(author, get(API + path), 200))), 200);
        var resubmitted = call(author, command(path + "/submit", lock(retryDraft)), 200);
        call(moderator, body(post(API + "/reviews/" + task(resubmitted) + "/decisions"),
                Map.of("lockVersion", 0, "decision", "APPROVED", "note", "首次正式归类")), 200);
        var published = call(reader, get(publicPath), 200);
        assertThat(published.path("revisionId")).isEqualTo(resubmitted.path("revisionId"));
        assertThat(published.path("section").path("id").asText()).isEqualTo(formal);
        assertThat(published.path("body").asText()).isEqualTo("归类后的正文[1]");
        assertThat(published.path("references").get(0).path("title").asText()).isEqualTo("新增候选文献");
        assertThat(published.path("community").path("publishedAt").asText()).isEqualTo(firstPublishedAt);
        assertThat(published.path("community").path("publicEditedAt").asText()).isGreaterThan(firstPublishedAt);
        assertThat(releaseMethod(published)).isEqualTo("MANUAL_REVIEW");
        assertThat(releaseMethod(oldPublic)).isEqualTo("LEGACY");

        var next = call(author, command(path + "/revise", lock(call(author, get(API + path), 200))), 200);
        var move = update(next, "再次移动", "候选正文");
        move.put("sectionId", other);
        call(author, body(patch(API + path), move), 409);
        assertThat(call(reader, get(publicPath), 200).path("section").path("id").asText()).isEqualTo(formal);
    }

    @Test
    void approvalMarksUpgradedPendingFormalArticleAsManuallyReleased() throws Exception {
        long author = account("CONTRIBUTOR"), reviewer = moderator();
        String formal = section();
        long article = migratedPublishedArticle(author, formal, "LEGACY");
        var previous = call(author, get(API + "/resources/" + article), 200);
        String title = "升级前已投稿", body = "  **历史正文仍为纯文本**  ";
        long revision = sql.insert("""
                INSERT INTO content_revisions(resource_id,version_no,base_revision_id,title,body,revision_state,
                    created_by,submitted_at) VALUES(?,2,?,?,?,'IN_REVIEW',?,UTC_TIMESTAMP(3))
                """, article, previous.path("revisionId").asText(), title, body, author);
        String hash = CommunitySourceHash.content(title, body, formal, "PLAIN_TEXT", List.of());
        db.update("""
                INSERT INTO community_revision_profiles(revision_id,resource_id,section_id,source_format,
                    format_policy_id,release_method,source_sha256,body_char_count)
                VALUES(?,?,?,'PLAIN_TEXT',1,'LEGACY',?,?)
                """, revision, article, formal, hash, body.codePointCount(0, body.length()));
        long task = sql.insert("INSERT INTO review_tasks(revision_id,submitted_by) VALUES(?,?)", revision, author);
        String reviewPath = API + "/reviews/" + task;
        var frozen = call(reviewer, get(reviewPath), 200).path("article");
        call(reviewer, body(post(reviewPath + "/decisions"),
                Map.of("lockVersion", 0, "decision", "APPROVED", "note", "通过升级前的投稿")), 200);

        var published = call(author, get(API + "/resources/" + article), 200);
        assertThat(published.path("revisionId").asText()).isEqualTo(Long.toString(revision));
        assertThat(releaseMethod(published)).isEqualTo("MANUAL_REVIEW");
        assertThat(releaseMethod(previous)).isEqualTo("LEGACY");
        var approvedSnapshot = call(reviewer, get(reviewPath), 200).path("article");
        for (String field : List.of("title", "body", "sectionId", "sourceFormat", "formatPolicyId", "sourceHash", "references")) {
            assertThat(approvedSnapshot.path(field)).isEqualTo(frozen.path(field));
        }
        assertThat(approvedSnapshot.path("sourceHash").asText()).isEqualTo(hash);
    }

    private String releaseMethod(JsonNode article) {
        return db.queryForObject("SELECT release_method FROM community_revision_profiles WHERE revision_id=?",
                String.class, article.path("revisionId").asText());
    }

    @Test
    void legacyClassificationRequiresSystemContainerAndMigratedPublishedSnapshot() throws Exception {
        long author = account("CONTRIBUTOR");
        String legacy = legacySection();
        long article = migratedPublishedArticle(author, legacy, "MANUAL_REVIEW");
        String path = "/me/articles/" + article;
        var draft = call(author, command(path + "/revise", lock(call(author, get(API + path), 200))), 200);
        var mutation = update(draft, "尝试归类", "正文");
        mutation.put("sectionId", section());
        call(author, body(patch(API + path), mutation), 409);

        // A row matching the code but with a creator is not the reserved system container.
        db.update("UPDATE community_sections SET created_by=? WHERE id=?", author, legacy);
        long spoofed = migratedPublishedArticle(author, legacy, "LEGACY");
        String spoofedPath = "/me/articles/" + spoofed;
        var spoofedDraft = call(author, command(spoofedPath + "/revise",
                lock(call(author, get(API + spoofedPath), 200))), 200);
        mutation.putAll(lock(spoofedDraft));
        call(author, body(patch(API + spoofedPath), mutation), 409);
    }

    private String legacySection() {
        var existing = db.queryForList("SELECT id FROM community_sections WHERE code='legacy-content'", String.class);
        if (!existing.isEmpty()) return existing.get(0);
        return Long.toString(sql.insert("""
                INSERT INTO community_sections(code,name,normalized_name,active,lifecycle)
                VALUES('legacy-content','历史内容','历史内容',0,'FORMAL')
                """));
    }

    private String newSection(String lifecycle) {
        String code = "section-" + UUID.randomUUID();
        return Long.toString(sql.insert("""
                INSERT INTO community_sections(name,normalized_name,code,lifecycle) VALUES(?,?,?,?)
                """, code, code, code, lifecycle));
    }

    private long migratedPublishedArticle(long author, String section, String release) {
        String title = "原公开标题";
        String body = "  原公开正文 **纯文本**  ";
        long article = sql.insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('ARTICLE',?)", author);
        long revision = sql.insert("""
                INSERT INTO content_revisions(resource_id,version_no,title,body,revision_state,created_by,approved_at)
                VALUES(?,1,?,?,'APPROVED',?,'2026-01-02 03:04:05')
                """, article, title, body, author);
        db.update("""
                INSERT INTO community_revision_profiles(revision_id,resource_id,section_id,source_format,
                    format_policy_id,release_method,source_sha256,body_char_count)
                VALUES(?,?,?,'PLAIN_TEXT',1,?,?,?)
                """, revision, article, section, release, CommunitySourceHash.content(title, body, section, "PLAIN_TEXT", List.of()),
                body.codePointCount(0, body.length()));
        db.update("""
                INSERT INTO community_post_profiles(resource_id,section_id,public_edited_at) VALUES(?,?,'2026-01-02 03:04:05')
                """, article, section);
        db.update("""
                UPDATE content_resources SET visibility_status='PUBLISHED',published_revision_id=?,
                    published_at='2026-01-02 03:04:05' WHERE id=?
                """, revision, article);
        return article;
    }

    @Test
    void approvalCannotRestoreGovernanceSuspension() throws Exception {
        long author = account("CONTRIBUTOR");
        long reviewer = moderator();
        long resource = sql.insert("INSERT INTO content_resources(resource_type,owner_id,visibility_status,suspended_reason) VALUES('ARTICLE',?,'SUSPENDED','治理暂停')", author);
        long revision = sql.insert("INSERT INTO content_revisions(resource_id,version_no,title,body,revision_state,created_by) VALUES(?,1,'待审核','正文','IN_REVIEW',?)", resource, author);
        long task = sql.insert("INSERT INTO review_tasks(revision_id,submitted_by) VALUES(?,?)", revision, author);
        call(reviewer, body(post(API + "/reviews/" + task + "/decisions"),
                Map.of("lockVersion", 0, "decision", "APPROVED", "note", "尝试越过治理")), 409);
        assertThat(db.queryForObject("SELECT visibility_status FROM content_resources WHERE id=?", String.class, resource)).isEqualTo("SUSPENDED");
        assertThat(db.queryForObject("SELECT status FROM review_tasks WHERE id=?", String.class, task)).isEqualTo("PENDING");
    }
}

abstract class CommunityArticleFixtures {
    static final String API = "/api/v1";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ContentSupport sql;
    @Autowired ObjectMapper json;

    long account(String... roles) {
        long account = sql.insert("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,'test','学员')",
                "art_" + UUID.randomUUID().toString().replace("-", ""));
        for (String role : roles) {
            db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code=?", account, role);
        }
        return account;
    }

    long moderator() {
        long reviewer = account();
        long role = sql.insert("INSERT INTO sys_roles(code,name) VALUES(?,'社区审核测试')",
                "TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        db.update("INSERT INTO sys_role_permissions(role_id,permission_id) SELECT ?,id FROM sys_permissions WHERE code='community:moderate'", role);
        db.update("INSERT INTO sys_user_roles(user_id,role_id) VALUES(?,?)", reviewer, role);
        return reviewer;
    }

    JsonNode call(long actor, MockHttpServletRequestBuilder request, int expected) throws Exception {
        return json.readTree(mvc.perform(request.with(user(Long.toString(actor))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object payload) throws Exception {
        return request.contentType("application/json").content(json.writeValueAsBytes(payload));
    }

    MockHttpServletRequestBuilder command(String path, Object payload) throws Exception {
        return command(path, payload, UUID.randomUUID().toString());
    }

    MockHttpServletRequestBuilder command(String path, Object payload, String key) throws Exception {
        return body(post(API + path).header("Idempotency-Key", key), payload);
    }

    String section() {
        return db.queryForObject("SELECT id FROM community_sections WHERE code='study'", String.class);
    }

    String task(JsonNode submitted) {
        return db.queryForObject("SELECT id FROM review_tasks WHERE revision_id=?", String.class, submitted.path("revisionId").asText());
    }

    Map<String, Object> lock(JsonNode value) {
        return Map.of("baseRevisionId", value.path("revisionId").asText(), "lockVersion", value.path("lockVersion").asInt());
    }

    Map<String, Object> update(JsonNode value, String title, String body) {
        var input = new java.util.HashMap<String, Object>(lock(value));
        input.put("title", title);
        input.put("body", body);
        return input;
    }

    Map<String, Object> reference(String key, int number, String title) {
        return Map.of("referenceKey", key, "displayNumber", number, "title", title);
    }
}
