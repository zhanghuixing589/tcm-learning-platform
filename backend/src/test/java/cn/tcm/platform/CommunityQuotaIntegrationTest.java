package cn.tcm.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommunityQuotaIntegrationTest extends CommunityArticleFixtures {
    @Test
    void firstLongArticleReceivesPrivateIdentityThenApprovedQuota() throws Exception {
        long author = account("CONTRIBUTOR");
        long admin = account("ADMIN");
        var identity = call(author, command("/me/articles/draft-identities", Map.of("title", "待扩容长文")), 201);
        assertThat(identity.path("body").asText()).isEmpty();
        String path = "/me/articles/" + identity.path("id").asText();
        call(author, command(path + "/submit", lock(identity)), 400);
        String source = "长".repeat(110000);
        var input = update(identity, "待扩容长文", source);
        input.put("sectionId", section());
        call(author, body(patch(API + path), input), 400);
        var request = call(author, command(path + "/quota-requests", Map.of("requestedLimit", 125000, "reason", "需保留全文")), 201);
        call(admin, command("/admin/community/quota-requests/" + request.path("id").asText() + "/decisions",
                Map.of("decision", "APPROVED", "approvedLimit", 120000, "lockVersion", 0, "note", "同意扩容")), 200);
        var quota = call(author, get(API + path + "/quota"), 200);
        assertThat(quota.path("bodyCharLimit").asInt()).isEqualTo(120000);
        assertThat(call(author, get(API + path), 200).path("body").asText()).isEmpty();
        var saved = call(author, body(patch(API + path), input), 200);
        assertThat(saved.path("body").asText()).isEqualTo(source);
        call(author, command(path + "/submit", lock(saved)), 200);
        call(author, get(API + "/resources/" + identity.path("id").asText()), 404);
    }

    @Test
    void quotaRequestsAreOwnedSinglePendingAndDecisionsAreIdempotent() throws Exception {
        long author = account("CONTRIBUTOR", "ADMIN");
        long admin = account("ADMIN");
        long outsider = account("CONTRIBUTOR");
        var draft = call(author, command("/me/articles", Map.of("title", "待扩容", "body", "草稿")), 201);
        String id = draft.path("id").asText();
        String path = "/me/articles/" + id + "/quota-requests";
        var input = Map.of("requestedLimit", 130000, "reason", "扩容理由");
        call(outsider, command(path, input), 404);
        call(outsider, get(API + "/admin/community/quota-requests"), 403);
        var request = call(author, command(path, input, "test-quota-request"), 201);
        assertThat(call(author, command(path, input, "test-quota-request"), 201)).isEqualTo(request);
        call(author, command(path, Map.of("requestedLimit", 140000, "reason", "同键改内容"), "test-quota-request"), 409);
        call(author, command(path, input), 409);
        assertThat(call(author, get(API + path), 200).path("total").asInt()).isEqualTo(1);
        assertThat(call(admin, get(API + "/admin/community/quota-requests"), 200).path("items").get(0).path("id"))
                .isEqualTo(request.path("id"));
        String requestId = request.path("id").asText();
        String decide = "/admin/community/quota-requests/" + requestId + "/decisions";
        var approve = Map.of("decision", "APPROVED", "approvedLimit", 120000, "lockVersion", 0, "note", "同意");
        call(author, command(decide, approve), 403);
        call(admin, command(decide, Map.of("decision", "APPROVED", "approvedLimit", 140000, "lockVersion", 0, "note", "无效")), 400);
        db.update("UPDATE content_resources SET visibility_status='SUSPENDED',suspended_reason='治理暂停' WHERE id=?", id);
        var approved = call(admin, command(decide, approve, "test-quota-decision"), 200);
        assertThat(call(admin, command(decide, approve, "test-quota-decision"), 200)).isEqualTo(approved);
        assertThat(call(admin, command(decide, approve), 200)).isEqualTo(approved);
        call(admin, command(decide, Map.of("decision", "REJECTED", "lockVersion", 1, "note", "改判")), 409);
        assertThat(db.queryForObject("SELECT visibility_status FROM content_resources WHERE id=?", String.class, id)).isEqualTo("SUSPENDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE event_key=?", Long.class, "article-quota:" + requestId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_audit_logs WHERE action='ARTICLE_QUOTA_APPROVED' AND object_key=?", Long.class, requestId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT body_char_limit FROM community_article_quotas WHERE resource_id=?", Integer.class, id)).isEqualTo(120000);
        db.update("""
                UPDATE community_text_policies p JOIN community_text_policy_heads h ON h.policy_id=p.id
                SET p.config=JSON_SET(p.config,'$.maxArticleChars',140000)
                """);
        assertThat(call(author, get(API + "/me/articles/" + id + "/quota"), 200).path("bodyCharLimit").asInt()).isEqualTo(140000);
        assertThat(db.queryForObject("SELECT body_char_limit FROM community_article_quotas WHERE resource_id=?", Integer.class, id)).isEqualTo(120000);
        db.update("DELETE FROM sys_user_roles WHERE user_id=?", admin);
        call(admin, command(decide, approve, "test-quota-decision"), 403);
    }

    @Test
    void deletingLastDraftWithdrawsPendingQuotaAndReplayCannotResurrectIt() throws Exception {
        long author = account("CONTRIBUTOR"), admin = account("ADMIN");
        var draft = call(author, command("/me/articles/draft-identities", Map.of("title", "即将删除"), "delete-identity"), 201);
        String id = draft.path("id").asText();
        String path = "/me/articles/" + id;
        var input = Map.of("requestedLimit", 130000, "reason", "暂不需要");
        var request = call(author, command(path + "/quota-requests", input, "delete-quota"), 201);
        call(author, delete(API + path).param("baseRevisionId", draft.path("revisionId").asText())
                .param("lockVersion", draft.path("lockVersion").asText()), 200);
        call(author, get(API + path), 404);
        call(author, command("/me/articles/draft-identities", Map.of("title", "即将删除"), "delete-identity"), 404);
        call(author, command(path + "/quota-requests", input, "delete-quota"), 404);
        var withdrawn = call(admin, get(API + "/admin/community/quota-requests").param("status", "WITHDRAWN"), 200)
                .path("items").get(0);
        assertThat(withdrawn.path("id")).isEqualTo(request.path("id"));
        assertThat(withdrawn.path("lockVersion").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?", Long.class, id)).isZero();
    }
}
