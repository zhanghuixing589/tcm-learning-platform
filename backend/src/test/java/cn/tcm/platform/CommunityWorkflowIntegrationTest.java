package cn.tcm.platform;

import cn.tcm.platform.content.ContentSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommunityWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ContentSupport sql;
    @Autowired ObjectMapper json;

    long account(String... roles) {
        long id = sql.insert("INSERT INTO sys_users(username,password_hash,nickname) VALUES(?,'test','学习者')",
                "cmt_" + UUID.randomUUID().toString().replace("-", ""));
        for (String role : roles) {
            db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code=?", id, role);
        }
        return id;
    }

    JsonNode call(long actor, MockHttpServletRequestBuilder request, int expected) throws Exception {
        var authorities = db.queryForList("""
                SELECT DISTINCT p.code FROM sys_permissions p
                JOIN sys_role_permissions rp ON rp.permission_id=p.id JOIN sys_user_roles ur ON ur.role_id=rp.role_id
                WHERE ur.user_id=?
                """, String.class, actor).stream().map(SimpleGrantedAuthority::new).toList();
        return json.readTree(mvc.perform(request.with(user(Long.toString(actor)).authorities(authorities)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object payload) throws Exception {
        return request.contentType("application/json").content(json.writeValueAsBytes(payload));
    }

    MockHttpServletRequestBuilder create(String path, Object payload, String key) throws Exception {
        return body(post("/api/v1" + path).header("Idempotency-Key", key), payload);
    }

    String section() {
        return db.queryForObject("SELECT id FROM community_sections WHERE code='study'", String.class);
    }

    JsonNode postIn(long actor, String section, String title, String key) throws Exception {
        return call(actor, create("/posts", Map.of("sectionId", section, "title", title, "body", "第一行\r\n  第二行  "), key), 201);
    }

    @Test
    void unknownCommunityEndpointReturnsStandardNotFound() throws Exception {
        long viewer = account("LEARNER");
        var response = mvc.perform(get("/api/v1/community/no-such-route").with(user(Long.toString(viewer))))
                .andExpect(status().isNotFound()).andReturn().getResponse();
        var payload = json.readTree(response.getContentAsByteArray());
        assertThat(payload.path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(payload.path("traceId").asText()).isNotBlank();
    }

    @Test
    void scopedSectionManagementAndRepeatMembershipFollowCurrentState() throws Exception {
        long owner = account("LEARNER");
        long admin = account("LEARNER", "ADMIN");
        long member = account("LEARNER", "REVIEWER");
        assertThat(call(member, get("/api/v1/community/sections"), 200).isArray()).isTrue();
        String code = "task3-" + UUID.randomUUID();
        var rootInput = Map.of("name", "讨论 " + code, "code", code, "ownerId", Long.toString(owner), "sortOrder", 5);
        call(member, create("/admin/community/sections", rootInput, "section-fail"), 403);
        var section = call(admin, create("/admin/community/sections", rootInput, "section-root"), 201);
        String root = section.path("id").asText();
        var child = call(admin, create("/admin/community/sections", Map.of("parentId", root, "name", " Ａ  一 ", "code", code + "-child", "sortOrder", 2), "section-child"), 201);
        assertThat(child.path("parentId").asText()).isEqualTo(root);
        call(admin, create("/admin/community/sections", Map.of("parentId", root, "name", "a 一", "code", code + "-dupe", "sortOrder", 2), "section-dupe"), 409);
        call(admin, create("/admin/community/sections", Map.of("parentId", child.path("id").asText(), "name", "第三层", "code", code + "-deep", "sortOrder", 2), "section-deep"), 400);

        String path = "/api/v1/community/sections/" + root;
        call(member, body(patch(path + "/profile"), Map.of("lockVersion", 0, "description", "禁止")), 403);
        call(owner, body(patch(path + "/profile"), Map.of("lockVersion", 0, "description", "介绍")), 200);
        call(owner, body(patch(path + "/profile"), Map.of("lockVersion", 1, "name", "改名")), 400);
        String otherPost = postIn(member, section(), "另一分区", "section-pin-other").path("id").asText();
        call(owner, body(put(path + "/posts/" + otherPost + "/pin"), Map.of("lockVersion", 0, "pinned", true)), 404);
        String localPost = postIn(member, root, "同分区", "section-pin-local").path("id").asText();
        assertThat(call(owner, body(put(path + "/posts/" + localPost + "/pin"), Map.of("lockVersion", 0, "pinned", true)), 200)
                .path("pinned").asBoolean()).isTrue();
        call(owner, delete(path + "/membership"), 409);
        var joined = call(member, put(path + "/membership"), 200);
        assertThat(joined.path("joined").asBoolean()).isTrue();
        assertThat(call(member, put(path + "/membership"), 200)).isEqualTo(joined);
        assertThat(call(member, get("/api/v1/community/sections").param("joinedOnly", "true"), 200).toString()).contains(root);
        var left = call(member, delete(path + "/membership"), 200);
        assertThat(left.path("joined").asBoolean()).isFalse();
        assertThat(call(member, delete(path + "/membership"), 200)).isEqualTo(left);
        call(member, put(path + "/membership"), 200);
        assertThat(sql.count("SELECT COUNT(*) FROM community_section_events WHERE section_id=? AND subject_user_id=?",
                root, member)).isEqualTo(3);
        call(owner, put(path + "/membership"), 200);
        call(admin, body(patch("/api/v1/admin/users/" + owner + "/status"), Map.of("status", "DISABLED")), 200);
        call(admin, body(patch("/api/v1/admin/users/" + owner + "/status"), Map.of("status", "ACTIVE")), 200);
        assertThat(db.queryForObject("SELECT state FROM community_section_members WHERE section_id=? AND user_id=?",
                String.class, root, owner)).isEqualTo("REMOVED");
        assertThat(db.queryForObject("SELECT management_status FROM community_sections WHERE id=? AND owner_id IS NULL",
                String.class, root)).isEqualTo("UNOWNED");
    }

    @Test
    void publicReadsUseOnlyPublicationPointerAndDetailAddsOneHistoryView() throws Exception {
        long owner = account("LEARNER");
        long other = account("LEARNER");
        long submitOnly = account("CONTRIBUTOR");
        call(submitOnly, create("/posts", Map.of("sectionId", section(), "title", "t", "body", "b"), "post-denied"), 403);
        var first = postIn(owner, section(), "第一次公开", "post-first");
        String id = first.path("id").asText();
        String firstRevision = first.path("revisionId").asText();
        assertThat(first.path("body").asText()).isEqualTo("第一行\n  第二行  ");
        assertThat(first.path("releaseMethod").asText()).isEqualTo("DIRECT");
        assertThat(postIn(owner, section(), "第一次公开", "post-first").path("id").asText()).isEqualTo(id);
        call(owner, create("/posts", Map.of("sectionId", section(), "title", "t", "body", "b"), "post-first"), 409);
        call(owner, create("/posts", Map.of("sectionId", section(), "title", "t", "body", "b", "resourceType", "ARTICLE"), "post-article"), 400);
        assertThat(sql.count("SELECT COUNT(*) FROM review_tasks WHERE revision_id=?", firstRevision)).isZero();
        String time = first.path("publishedAt").asText();

        String ownerPath = "/api/v1/me/posts/" + id;
        int lock = call(owner, get(ownerPath), 200).path("lockVersion").intValue();
        var edit = Map.of("baseRevisionId", firstRevision, "lockVersion", lock, "title", "第二次公开", "body", "改稿");
        var second = call(owner, create("/posts/" + id + "/revisions", edit, "post-edit-1"), 201);
        String secondRevision = second.path("revisionId").asText();
        assertThat(secondRevision).isNotEqualTo(firstRevision);
        assertThat(second.path("publishedAt").asText()).isEqualTo(time);
        assertThat(db.queryForObject("SELECT body FROM content_revisions WHERE id=?", String.class, firstRevision))
                .isEqualTo("第一行\n  第二行  ");
        call(owner, create("/posts/" + id + "/revisions", edit, "post-edit-2"), 409);
        long candidate = sql.insert("""
                INSERT INTO content_revisions(resource_id,version_no,base_revision_id,title,body,created_by)
                VALUES(?,3,?,'隐私候选标题','隐私候选正文',?)
                """, id, secondRevision, owner);
        var own = call(owner, get(ownerPath), 200);
        assertThat(own.path("latestRevision").path("revisionId").asText()).isEqualTo(Long.toString(candidate));
        assertThat(own.path("publishedRevisionId").asText()).isEqualTo(secondRevision);
        assertThat(call(owner, get(ownerPath + "/revisions"), 200).path("items").size()).isEqualTo(3);
        String diff = "/api/v1/me/community-revisions/diff?fromRevisionId=" + firstRevision + "&toRevisionId=" + candidate;
        assertThat(call(owner, get(diff), 200).toString()).contains("隐私候选正文");
        call(other, get(diff), 404);
        call(other, get(ownerPath), 404);

        assertThat(call(other, get("/api/v1/posts").param("q", "隐私候选"), 200).path("total").asLong()).isZero();
        assertThat(call(other, get("/api/v1/posts").param("sort", "LATEST_REPLY"), 200).toString()).doesNotContain("隐私候选");
        call(other, get("/api/v1/posts").param("sort", "INVALID"), 400);
        var detail = call(other, get("/api/v1/posts/" + id), 200);
        assertThat(detail.path("body").asText()).isEqualTo("改稿");
        assertThat(detail.toString()).doesNotContain("隐私候选", "candidateRevisionId");
        call(other, get("/api/v1/posts/" + id + "/comments"), 200);
        call(other, put("/api/v1/resources/" + id + "/bookmark"), 200);
        assertThat(sql.count("SELECT view_count FROM user_resource_history WHERE user_id=? AND resource_id=?", other, id)).isEqualTo(1);
        assertThat(call(other, get("/api/v1/me/bookmarks"), 200).toString()).contains("第二次公开").doesNotContain("隐私候选");
        assertThat(call(other, get("/api/v1/resources/" + id), 200).path("body").asText()).isEqualTo("改稿");
        db.update("UPDATE content_resources SET visibility_status='SUSPENDED' WHERE id=?", id);
        call(other, get("/api/v1/posts/" + id), 404);
        call(owner, create("/posts", Map.of("sectionId", section(), "title", "第一次公开", "body", "第一行\r\n  第二行  "), "post-first"), 404);
        call(owner, create("/posts/" + id + "/revisions", edit, "post-reopen"), 409);
        assertThat(call(other, get("/api/v1/me/history"), 200).toString()).contains("UNAVAILABLE").doesNotContain("改稿");
    }

    @Test
    void pinDoesNotDistinguishAnUnpublishedArticleFromAMissingResource() throws Exception {
        long owner = account("LEARNER");
        long viewer = account("LEARNER");
        String sectionId = section();
        long unpublished = sql.insert("INSERT INTO content_resources(resource_type,owner_id) VALUES('ARTICLE',?)", owner);
        db.update("INSERT INTO community_post_profiles(resource_id,section_id) VALUES(?,?)", unpublished, sectionId);
        String published = postIn(owner, sectionId, "公开讨论", "pin-public-post").path("id").asText();
        String prefix = "/api/v1/community/sections/" + sectionId + "/posts/";
        var input = Map.of("lockVersion", 0, "pinned", true);
        call(viewer, body(put(prefix + unpublished + "/pin"), input), 404);
        call(viewer, body(put(prefix + Long.MAX_VALUE + "/pin"), input), 404);
        call(viewer, body(put(prefix + published + "/pin"), input), 403);
    }

    @Test
    void postRevisionReplayIdentifiesOriginalStillAuthorizedVersion() throws Exception {
        long owner = account("LEARNER");
        var first = postIn(owner, section(), "发布", "post-replay-initial");
        String path = "/posts/" + first.path("id").asText() + "/revisions";
        var edit = Map.of("baseRevisionId", first.path("revisionId").asText(), "lockVersion", 0,
                "title", "版本二", "body", "第一次修订");
        var second = call(owner, create(path, edit, "post-replay-edit"), 201);
        var third = call(owner, create(path, Map.of("baseRevisionId", second.path("revisionId").asText(),
                "lockVersion", 1, "title", "版本三", "body", "第二次修订"), "post-replay-later"), 201);
        assertThat(third.path("revisionId")).isNotEqualTo(second.path("revisionId"));
        var replay = call(owner, create(path, edit, "post-replay-edit"), 201);
        assertThat(replay.path("revisionId")).isEqualTo(second.path("revisionId"));
        assertThat(replay.path("body").asText()).isEqualTo("第一次修订");
    }

    @Test
    void commentRevisionReplayIdentifiesOriginalVersionAndRespectsDeletion() throws Exception {
        long owner = account("LEARNER");
        String postId = postIn(owner, section(), "评论重试", "comment-replay-post").path("id").asText();
        var first = call(owner, create("/posts/" + postId + "/comments", Map.of("body", "根评论"), "comment-replay-root"), 201);
        String id = first.path("id").asText();
        String path = "/comments/" + id + "/revisions";
        var edit = Map.of("baseRevisionId", first.path("revisionId").asText(), "lockVersion", 0, "body", "第一次修订");
        var second = call(owner, create(path, edit, "comment-replay-edit"), 201);
        var third = call(owner, create(path, Map.of("baseRevisionId", second.path("revisionId").asText(),
                "lockVersion", 1, "body", "第二次修订"), "comment-replay-later"), 201);
        assertThat(third.path("revisionId")).isNotEqualTo(second.path("revisionId"));
        var replay = call(owner, create(path, edit, "comment-replay-edit"), 201);
        assertThat(replay.path("revisionId")).isEqualTo(second.path("revisionId"));
        assertThat(replay.path("body").asText()).isEqualTo("第一次修订");
        call(owner, delete("/api/v1/comments/" + id).param("lockVersion", "2"), 200);
        var deletedReplay = call(owner, create(path, edit, "comment-replay-edit"), 201);
        assertThat(deletedReplay.path("displayState").asText()).isEqualTo("DELETED");
        assertThat(deletedReplay.path("body").isNull()).isTrue();
    }

    @Test
    void commentThreadsKeepSafePlaceholdersAndFrozenPublishedRevisions() throws Exception {
        long owner = account("LEARNER");
        long other = account("LEARNER");
        String id = postIn(owner, section(), "回复串", "comment-post-1").path("id").asText();
        String otherId = postIn(owner, section(), "另一个帖子", "comment-post-2").path("id").asText();
        String path = "/posts/" + id + "/comments";
        var root = call(owner, create(path, Map.of("body", "根正文"), "comment-root"), 201);
        String rootId = root.path("id").asText();
        String secondRoot = call(other, create(path, Map.of("body", "另一个根"), "comment-root2"), 201).path("id").asText();
        var reply = call(other, create(path, Map.of("body", "初次回复", "rootCommentId", rootId, "replyToCommentId", rootId), "comment-reply"), 201);
        String replyId = reply.path("id").asText();
        call(owner, create(path, Map.of("body", "跨串", "rootCommentId", secondRoot, "replyToCommentId", replyId), "comment-cross-thread"), 400);
        call(owner, create("/posts/" + otherId + "/comments", Map.of("body", "跨帖", "rootCommentId", rootId, "replyToCommentId", rootId), "comment-cross-post"), 400);
        call(owner, create(path, Map.of("body", "回复到回复", "rootCommentId", rootId, "replyToCommentId", replyId), "comment-nested-reply"), 201);
        String revision = reply.path("revisionId").asText();
        long candidate = sql.insert("""
                INSERT INTO community_comment_revisions(comment_id,version_no,base_revision_id,body,revision_state,release_method,
                    format_policy_id,source_sha256,body_char_count,created_by)
                VALUES (?,2,?,'评论候选秘密','DRAFT','NONE',1,REPEAT('0',64),6,?)
                """, replyId, revision, other);
        db.update("UPDATE community_comments SET candidate_revision_id=? WHERE id=?", candidate, replyId);
        var visibleReplies = call(owner, get("/api/v1" + path).param("rootId", rootId), 200);
        assertThat(visibleReplies.toString()).contains("初次回复").doesNotContain("评论候选秘密", "candidateRevisionId");
        var revised = call(other, create("/comments/" + replyId + "/revisions",
                Map.of("baseRevisionId", revision, "lockVersion", reply.path("lockVersion").asInt(), "body", "<b>按文本保存</b>"), "comment-edit"), 201);
        assertThat(revised.path("revisionId").asText()).isNotEqualTo(revision);
        assertThat(db.queryForObject("SELECT body FROM community_comment_revisions WHERE id=?", String.class, revision)).isEqualTo("初次回复");
        db.update("UPDATE community_post_profiles SET comments_enabled=0 WHERE resource_id=?", id);
        call(owner, create(path, Map.of("body", "评论关闭后的新内容"), "comment-disabled"), 409);
        var deleted = call(owner, delete("/api/v1/comments/" + rootId).param("lockVersion", root.path("lockVersion").asText()), 200);
        assertThat(deleted.path("body").isNull()).isTrue();
        assertThat(deleted.path("displayState").asText()).isEqualTo("DELETED");
        var replay = call(owner, create(path, Map.of("body", "根正文"), "comment-root"), 201);
        assertThat(replay.path("body").isNull()).isTrue();
        assertThat(replay.path("displayState").asText()).isEqualTo("DELETED");
        call(owner, delete("/api/v1/comments/" + rootId).param("lockVersion", root.path("lockVersion").asText()), 200);
        db.update("UPDATE community_post_profiles SET comments_enabled=1 WHERE resource_id=?", id);
        call(other, create(path, Map.of("body", "回复被删除目标", "rootCommentId", rootId, "replyToCommentId", rootId), "comment-reply-deleted"), 409);
        var roots = call(other, get("/api/v1" + path).param("pageSize", "1"), 200);
        assertThat(roots.path("total").asInt()).isEqualTo(2);
        assertThat(roots.toString()).doesNotContain("根正文", "按文本保存");
        assertThat(call(owner, get("/api/v1" + path).param("rootId", rootId).param("pageSize", "1"), 200).path("total").asInt()).isEqualTo(2);
        db.update("UPDATE sys_users SET status='DISABLED' WHERE id=?", other);
        assertThat(call(owner, get("/api/v1" + path).param("rootId", rootId), 200).toString()).doesNotContain("按文本保存");
    }
}
