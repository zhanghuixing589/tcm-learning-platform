package cn.tcm.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @AutoConfigureMockMvc @Transactional
class PersonalCenterIntegrationTest {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path avatarDirectory;
    @org.springframework.test.context.DynamicPropertySource
    static void avatarStorage(org.springframework.test.context.DynamicPropertyRegistry properties) {properties.add("tcm.storage.avatar-directory",()->avatarDirectory.toString());}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired org.springframework.boot.autoconfigure.web.servlet.MultipartProperties multipartProperties;
    @Autowired cn.tcm.platform.account.application.AvatarService avatars;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private static String passwordHash;
    record User(long id,String token) {}
    private User user() throws Exception {
        if(passwordHash==null) passwordHash=encoder.encode("TestPass891!");
        String username="pc_"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO sys_users(username,password_hash,nickname,bio) VALUES(?,?,?,?)",username,passwordHash,"学习者","公开简介");
        long id=db.queryForObject("SELECT id FROM sys_users WHERE username=?",Long.class,username);
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code='LEARNER'",id);
        JsonNode data=call(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password","TestPass891!"))),200);
        return new User(id,data.path("accessToken").asText());
    }
    private MockHttpServletRequestBuilder as(User u,MockHttpServletRequestBuilder b) {return b.header("Authorization","Bearer "+u.token());}
    private JsonNode call(MockHttpServletRequestBuilder b,int expected) throws Exception {
        return json.readTree(mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private long resource(User owner,boolean published) {
        db.update("INSERT INTO content_resources(resource_type,owner_id) VALUES('ARTICLE',?)",owner.id());
        long id=db.queryForObject("SELECT MAX(id) FROM content_resources WHERE owner_id=?",Long.class,owner.id());
        db.update("INSERT INTO content_revisions(resource_id,version_no,title,body,revision_state,created_by) VALUES(?,1,?,?,?,?)",id,"公开标题","公开正文",published?"APPROVED":"DRAFT",owner.id());
        if(published) db.update("UPDATE content_resources SET visibility_status='PUBLISHED',published_revision_id=(SELECT id FROM content_revisions WHERE resource_id=?),published_at=UTC_TIMESTAMP(3) WHERE id=?",id,id);
        return id;
    }
    @Test void summaryAndPublicProfileProtectPrivateFields() throws Exception {
        User a=user(),b=user();
        JsonNode summary=call(as(a,get("/api/v1/me/profile-summary")),200);
        assertThat(summary.path("profile").path("id").asText()).isEqualTo(""+a.id());
        assertThat(summary.path("followingCount").asInt()).isZero();
        JsonNode profile=call(as(b,get("/api/v1/users/"+a.id()+"/profile")),200);
        assertThat(profile.path("profile").has("username")).isFalse();
        assertThat(profile.path("profile").has("permissions")).isFalse();
        mvc.perform(get("/api/v1/me/bookmarks")).andExpect(status().isUnauthorized());
    }
    @Test void publicIdentityDoesNotExposeBackOfficeRoles() throws Exception {
        User owner=user(),visitor=user();
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code='ADMIN'",owner.id());sqlSession.clearCache();
        JsonNode profile=call(as(visitor,get("/api/v1/users/"+owner.id()+"/profile")),200);
        assertThat(profile.path("profile").path("roles").toString()).doesNotContain("ADMIN");
    }
    @Test void receivedLikesSortByEffectiveLikeCountBeforeLatestLikeTime() throws Exception {
        User owner=user(),one=user(),two=user();long popular=resource(owner,true),recent=resource(owner,true);
        db.update("INSERT INTO resource_likes(user_id,resource_id,created_at) VALUES(?,?,UTC_TIMESTAMP(3)-INTERVAL 1 DAY),(?,?,UTC_TIMESTAMP(3)-INTERVAL 1 DAY),(?,?,UTC_TIMESTAMP(3))",one.id(),popular,two.id(),popular,one.id(),recent);
        JsonNode items=call(as(owner,get("/api/v1/me/received-likes")),200).path("items");
        assertThat(items.get(0).path("id").asText()).isEqualTo(""+popular);
        assertThat(items.get(0).path("likeCount").asInt()).isEqualTo(2);
    }
    @Test void ownerCanReadActualSuspensionReasonWithoutLeakingItPublicly() throws Exception {
        User owner=user(),visitor=user();long id=resource(owner,true);
        db.update("UPDATE content_resources SET visibility_status='SUSPENDED',suspended_reason='需补充来源说明' WHERE id=?",id);
        assertThat(call(as(owner,get("/api/v1/me/articles/"+id)),200).path("stateReason").asText()).isEqualTo("需补充来源说明");
        call(as(visitor,get("/api/v1/resources/"+id)),404);
    }
    @Test void sourceNameComesOnlyFromVisibleRevisionApprovedActiveEvidence() throws Exception {
        User owner=user(),visitor=user();long id=resource(owner,true);
        long revision=db.queryForObject("SELECT published_revision_id FROM content_resources WHERE id=?",Long.class,id);
        db.update("INSERT INTO source_documents(title,source_type,source_url,submitted_by,review_state) VALUES('来源书名','WEB','https://example.org/source',?,'APPROVED')",owner.id());
        long document=db.queryForObject("SELECT MAX(id) FROM source_documents WHERE submitted_by=?",Long.class,owner.id());
        db.update("INSERT INTO revision_evidence(revision_id,document_id,location_label,quote_text) VALUES(?,?,'第1页','依据片段')",revision,document);
        call(as(visitor,put("/api/v1/resources/"+id+"/bookmark")),200);
        JsonNode item=call(as(visitor,get("/api/v1/me/bookmarks")),200).path("items").get(0);
        assertThat(item.path("sourceName").asText()).isEqualTo("来源书名");
        db.update("UPDATE source_documents SET availability='SUSPENDED' WHERE id=?",document);
        assertThat(call(as(visitor,get("/api/v1/me/bookmarks")),200).path("items").get(0).path("sourceName").isNull()).isTrue();
    }
    @Test void privatePostPublicationHasOwnerOnlyReadRouteWithoutArticleEditing() throws Exception {
        User owner=user(),visitor=user();long id=resource(owner,false);
        db.update("UPDATE content_resources SET resource_type='POST' WHERE id=?",id);
        JsonNode own=call(as(owner,get("/api/v1/me/publications/"+id)),200);
        assertThat(own.path("resourceType").asText()).isEqualTo("POST");
        assertThat(own.path("body").asText()).isEqualTo("公开正文");
        call(as(visitor,get("/api/v1/me/publications/"+id)),404);
        call(as(owner,get("/api/v1/me/articles/"+id)),404);
        call(as(visitor,get("/api/v1/resources/"+id)),404);
    }
    @Test void resourceDetailIncludesActualPublicationTimeAndEffectiveCounts() throws Exception {
        User owner=user(),visitor=user();long id=resource(owner,true);
        db.update("INSERT INTO resource_likes(user_id,resource_id) VALUES(?,?),(?,?)",owner.id(),id,visitor.id(),id);
        JsonNode detail=call(as(visitor,get("/api/v1/resources/"+id)),200);
        assertThat(detail.path("occurredAt").asText()).endsWith("Z");
        assertThat(detail.path("likeCount").asInt()).isEqualTo(1);
        assertThat(detail.path("summary").asText()).isEqualTo("公开正文");
    }
    @Test void datetimesRemainUtcWithoutSystemTimezoneShift() throws Exception {
        User owner=user(),visitor=user();long resource=resource(owner,true);
        JsonNode summary=call(as(owner,get("/api/v1/me/profile-summary")),200);
        String created=db.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') FROM sys_users WHERE id=?",String.class,owner.id());
        assertThat(java.time.Instant.parse(summary.path("profile").path("createdAt").asText())).isEqualTo(java.time.Instant.parse(created));
        call(as(visitor,put("/api/v1/resources/"+resource+"/bookmark")),200);
        String saved=db.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') FROM resource_bookmarks WHERE user_id=? AND resource_id=?",String.class,visitor.id(),resource);
        JsonNode item=call(as(visitor,get("/api/v1/me/bookmarks")),200).path("items").get(0);
        assertThat(java.time.Instant.parse(item.path("occurredAt").asText())).isEqualTo(java.time.Instant.parse(saved));
    }
    @Test void followingIsIdempotentMutualAndFiltersDisabledUsers() throws Exception {
        User a=user(),b=user();
        for(int i=0;i<2;i++) call(as(a,put("/api/v1/users/"+b.id()+"/follow")),200);
        call(as(b,put("/api/v1/users/"+a.id()+"/follow")),200);
        call(as(a,put("/api/v1/users/"+a.id()+"/follow")),400);
        JsonNode list=call(as(a,get("/api/v1/me/following")),200);
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("followsYou").asBoolean()).isTrue();
        db.update("UPDATE sys_users SET status='DISABLED' WHERE id=?",b.id());
        assertThat(call(as(a,get("/api/v1/me/profile-summary")),200).path("followingCount").asInt()).isZero();
        assertThat(call(as(a,get("/api/v1/me/followers")),200).path("total").asInt()).isZero();
        call(as(a,put("/api/v1/users/"+b.id()+"/follow")),404);
        for(int i=0;i<2;i++) call(as(a,delete("/api/v1/users/"+b.id()+"/follow")),200);
    }
    @Test void publishedPointerNeverLeaksPendingRevisionAndHistoryIsAggregated() throws Exception {
        User owner=user(),reader=user();long id=resource(owner,true);
        db.update("INSERT INTO content_revisions(resource_id,version_no,title,body,revision_state,created_by) VALUES(?,2,'秘密修订标题','秘密修订正文','IN_REVIEW',?)",id,owner.id());
        for(int i=0;i<2;i++) {
            JsonNode detail=call(as(reader,get("/api/v1/resources/"+id)),200);
            assertThat(detail.path("title").asText()).isEqualTo("公开标题");
            assertThat(detail.path("body").asText()).isEqualTo("公开正文");
        }
        JsonNode items=call(as(reader,get("/api/v1/users/"+owner.id()+"/publications")),200);
        assertThat(items.path("total").asInt()).isEqualTo(1);
        assertThat(items.path("items").get(0).path("revisionState").asText()).isEqualTo("APPROVED");
        JsonNode own=call(as(owner,get("/api/v1/me/publications?status=IN_REVIEW")),200);
        assertThat(own.path("total").asInt()).isEqualTo(1);
        assertThat(own.path("items").get(0).path("visibilityStatus").asText()).isEqualTo("PUBLISHED");
        assertThat(db.queryForObject("SELECT view_count FROM user_resource_history WHERE user_id=? AND resource_id=?",Integer.class,reader.id(),id)).isEqualTo(2);
        assertThat(call(as(owner,get("/api/v1/me/history")),200).path("total").asInt()).isZero();
        call(as(reader,delete("/api/v1/me/history/"+id)),200);
        assertThat(call(as(reader,get("/api/v1/me/history")),200).path("total").asInt()).isZero();
    }
    @Test void bookmarkAndLikeAreIdempotentAndUnavailablePlaceholdersHideMetadata() throws Exception {
        User owner=user(),reader=user();long id=resource(owner,true);
        for(int i=0;i<2;i++) {call(as(reader,put("/api/v1/resources/"+id+"/bookmark")),200);call(as(reader,put("/api/v1/resources/"+id+"/like")),200);}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM resource_likes WHERE resource_id=?",Integer.class,id)).isEqualTo(1);
        call(as(reader,get("/api/v1/resources/"+id)),200);
        db.update("UPDATE content_resources SET visibility_status='SUSPENDED' WHERE id=?",id);
        for(String path:List.of("bookmarks","history","likes")) {
            JsonNode item=call(as(reader,get("/api/v1/me/"+path)),200).path("items").get(0);
            assertThat(item.path("available").asBoolean()).isFalse();
            assertThat(item.path("title").asText()).doesNotContain("公开标题");
            assertThat(item.path("summary").isNull()).isTrue();
            assertThat(call(as(reader,get("/api/v1/me/"+path+"?q=公开标题")),200).path("total").asInt()).isZero();
        }
        call(as(reader,get("/api/v1/resources/"+id)),404);
        call(as(reader,put("/api/v1/resources/"+id+"/like")),404);
        for(int i=0;i<2;i++) {call(as(reader,delete("/api/v1/resources/"+id+"/like")),200);call(as(reader,delete("/api/v1/resources/"+id+"/bookmark")),200);}
    }
    @Test void receivedLikesExcludeSelfDisabledUsersAndUnavailableResources() throws Exception {
        User owner=user(),reader=user(),disabled=user();long id=resource(owner,true),hidden=resource(owner,false);
        for(User u:List.of(owner,reader,disabled)) db.update("INSERT INTO resource_likes(user_id,resource_id) VALUES(?,?)",u.id(),id);
        db.update("INSERT INTO resource_likes(user_id,resource_id) VALUES(?,?)",reader.id(),hidden);
        db.update("UPDATE sys_users SET status='DISABLED' WHERE id=?",disabled.id());
        assertThat(call(as(owner,get("/api/v1/me/profile-summary")),200).path("receivedLikes").asInt()).isEqualTo(1);
        JsonNode list=call(as(owner,get("/api/v1/me/received-likes")),200);
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("likeCount").asInt()).isEqualTo(1);
        call(as(reader,get("/api/v1/resources/"+hidden)),404);
    }
    @Test void invalidFiltersAndPaginationReturnControlledErrors() throws Exception {
        User u=user();
        for(String filter:List.of("page=0","pageSize=101","type=UNKNOWN","status=bogus","q="+"x".repeat(101)))
            call(as(u,get("/api/v1/me/bookmarks?"+filter)),400);
        call(as(u,get("/api/v1/me/following?page=-1")),400);
        call(as(u,get("/api/v1/resources/not-a-number")),400);
    }
    @Test void articleDraftsRequirePermissionAndFrozenSubmissionsGetReviewTasks() throws Exception {
        User owner=user(),other=user();
        String body="{\"title\":\"我的文章\",\"body\":\"真实草稿正文\"}";
        call(as(owner,post("/api/v1/me/articles").contentType("application/json").content(body)),403);
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code='CONTRIBUTOR'",owner.id());
        sqlSession.clearCache(); // Direct fixture mutation bypasses MyBatis inside the test's shared transaction.
        JsonNode draft=call(as(owner,post("/api/v1/me/articles").contentType("application/json").content(body)),201);
        String id=draft.path("id").asText();
        call(as(other,get("/api/v1/me/articles/"+id)),404);
        call(as(owner,post("/api/v1/me/articles/"+id+"/submit")),200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM review_tasks t JOIN content_revisions v ON v.id=t.revision_id WHERE v.resource_id=? AND t.status='PENDING'",Integer.class,id)).isEqualTo(1);
        call(as(owner,patch("/api/v1/me/articles/"+id).contentType("application/json").content(body)),409);
        call(as(owner,get("/api/v1/resources/"+id)),404);
        call(as(owner,post("/api/v1/me/articles/"+id+"/withdraw")),200);
        assertThat(db.queryForObject("SELECT status FROM review_tasks t JOIN content_revisions v ON v.id=t.revision_id WHERE v.resource_id=?",String.class,id)).isEqualTo("WITHDRAWN");
        call(as(owner,patch("/api/v1/me/articles/"+id).contentType("application/json").content(body)),409);
        JsonNode revised=call(as(owner,post("/api/v1/me/articles/"+id+"/revise")),200);
        assertThat(revised.path("revisionState").asText()).isEqualTo("DRAFT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_revisions WHERE resource_id=?",Integer.class,id)).isEqualTo(2);
        call(as(owner,delete("/api/v1/me/articles/"+id)),200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_revisions WHERE resource_id=? AND revision_state='WITHDRAWN'",Integer.class,id)).isEqualTo(1);
    }
    @Test void avatarRequiresActualBoundedImageAndServesOnlyVerifiedPng() throws Exception {
        User u=user();
        var fake=new MockMultipartFile("file","avatar.png","image/png","<script>bad</script>".getBytes());
        call(as(u,multipart("/api/v1/me/avatar").file(fake)),400);
        var tooBig=new MockMultipartFile("file","large.png","image/png",new byte[2*1024*1024+1]);
        call(as(u,multipart("/api/v1/me/avatar").file(tooBig)),400);
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"png",out);
        JsonNode uploaded=call(as(u,multipart("/api/v1/me/avatar").file(new MockMultipartFile("file","../../test.svg","image/png",out.toByteArray()))),200);
        assertThat(uploaded.path("avatarUrl").asText()).isEqualTo("/api/v1/users/"+u.id()+"/avatar");
        mvc.perform(as(u,get(uploaded.path("avatarUrl").asText()))).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        assertThat(call(as(u,get("/api/v1/me/profile-summary")),200).path("profile").path("avatarUrl").asText()).isEqualTo(uploaded.path("avatarUrl").asText());
    }
    private MockMultipartFile validAvatar() throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"png",bytes);
        return new MockMultipartFile("file","avatar.png","image/png",bytes.toByteArray());
    }
    private String avatarKey(User u) {return db.queryForObject("SELECT avatar_path FROM sys_users WHERE id=?",String.class,u.id());}
    private Set<String> avatarFiles() throws Exception {
        java.nio.file.Files.createDirectories(avatarDirectory);
        try(var files=java.nio.file.Files.list(avatarDirectory)){return files.map(path->path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());}
    }
    private void removeAvatarFixture(User u,Set<String> originalFiles) throws Exception {
        db.update("DELETE FROM auth_refresh_tokens WHERE user_id=?",u.id());db.update("DELETE FROM sys_user_roles WHERE user_id=?",u.id());db.update("DELETE FROM sys_users WHERE id=?",u.id());
        for(String file:avatarFiles())if(!originalFiles.contains(file))java.nio.file.Files.deleteIfExists(avatarDirectory.resolve(file));
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void avatarReplacementCleansCommittedOldFileAndPreservesOldOnInvalidUploadOrRollback() throws Exception {
        User u=user();Set<String> originalFiles=avatarFiles();
        try {
            call(as(u,multipart("/api/v1/me/avatar").file(validAvatar())),200);String first=avatarKey(u);
            call(as(u,multipart("/api/v1/me/avatar").file(new MockMultipartFile("file","bad.png","image/png","invalid".getBytes()))),400);
            assertThat(avatarKey(u)).isEqualTo(first);assertThat(java.nio.file.Files.exists(avatarDirectory.resolve(first))).isTrue();
            MockMultipartFile replacement=validAvatar();
            String rolledBack=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                avatars.upload(u.id(),replacement);String candidate=avatarKey(u);
                assertThat(java.nio.file.Files.exists(avatarDirectory.resolve(first))).isTrue();
                status.setRollbackOnly();return candidate;
            });
            assertThat(avatarKey(u)).isEqualTo(first);assertThat(java.nio.file.Files.exists(avatarDirectory.resolve(first))).isTrue();
            assertThat(java.nio.file.Files.exists(avatarDirectory.resolve(rolledBack))).isFalse();
            call(as(u,multipart("/api/v1/me/avatar").file(validAvatar())),200);String current=avatarKey(u);
            assertThat(current).isNotEqualTo(first);assertThat(java.nio.file.Files.exists(avatarDirectory.resolve(first))).isFalse();
            Set<String> created=avatarFiles();created.removeAll(originalFiles);assertThat(created).containsExactly(current);
            mvc.perform(as(u,get("/api/v1/users/"+u.id()+"/avatar"))).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        } finally {removeAvatarFixture(u,originalFiles);}
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentAvatarReplacementsLeaveOnlyCommittedCurrentFile() throws Exception {
        User u=user();Set<String> originalFiles=avatarFiles();var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            MockMultipartFile first=validAvatar(),second=validAvatar();
            var one=workers.submit(()->avatars.upload(u.id(),first));var two=workers.submit(()->avatars.upload(u.id(),second));
            one.get(15,java.util.concurrent.TimeUnit.SECONDS);two.get(15,java.util.concurrent.TimeUnit.SECONDS);
            Set<String> created=avatarFiles();created.removeAll(originalFiles);assertThat(created).containsExactly(avatarKey(u));
        } finally {workers.shutdownNow();removeAvatarFixture(u,originalFiles);}
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void avatarReplacementNeverDeletesAnArbitraryPreviousPath() throws Exception {
        User u=user();Set<String> originalFiles=avatarFiles();var outside=java.nio.file.Files.createTempFile("tcm-avatar-sentinel-",".png");
        try {
            db.update("UPDATE sys_users SET avatar_path=? WHERE id=?",outside.toString(),u.id());
            call(as(u,multipart("/api/v1/me/avatar").file(validAvatar())),200);
            assertThat(java.nio.file.Files.exists(outside)).isTrue();
        } finally {java.nio.file.Files.deleteIfExists(outside);removeAvatarFixture(u,originalFiles);}
    }
    @Test void missingAvatarIsControlled() throws Exception {
        User u=user();
        call(as(u,multipart("/api/v1/me/avatar")),400);
    }
    @Test void transportLimitMatchesDocumentedAvatarLimit() {
        assertThat(multipartProperties.getMaxFileSize().toBytes()).isEqualTo(2*1024*1024);
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void oversizedMultipartIsControlledBeforeControllerResolution() throws Exception {
        User u=user();
        try {
            String boundary="TcmAvatarBoundary";
            var bytes=new ByteArrayOutputStream();
            bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes.write(new byte[2*1024*1024+1]);bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/me/avatar"))
                    .header("Authorization","Bearer "+u.token()).header("Content-Type","multipart/form-data; boundary="+boundary)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
            var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        } finally {
            db.update("DELETE FROM auth_refresh_tokens WHERE user_id=?",u.id());
            db.update("DELETE FROM sys_user_roles WHERE user_id=?",u.id());db.update("DELETE FROM sys_users WHERE id=?",u.id());
        }
    }
    @Test void paginationSearchAndHistoryClearStayWithinCurrentOwner() throws Exception {
        User a=user(),b=user();long first=resource(a,true),second=resource(a,true);
        db.update("UPDATE content_revisions SET title='含%百分号' WHERE resource_id=?",first);
        for(long resource:List.of(first,second)) call(as(b,put("/api/v1/resources/"+resource+"/bookmark")),200);
        JsonNode page1=call(as(b,get("/api/v1/me/bookmarks?pageSize=1")),200);
        JsonNode page2=call(as(b,get("/api/v1/me/bookmarks?pageSize=1&page=2")),200);
        assertThat(page1.path("total").asInt()).isEqualTo(2);
        assertThat(page1.path("items").get(0).path("id").asText()).isEqualTo(""+second);
        assertThat(page2.path("items").get(0).path("id").asText()).isEqualTo(""+first);
        assertThat(call(as(b,get("/api/v1/me/bookmarks").param("q","%")),200).path("total").asInt()).isEqualTo(1);
        call(as(a,get("/api/v1/resources/"+first)),200);call(as(b,get("/api/v1/resources/"+first)),200);
        call(as(b,delete("/api/v1/me/history")),200);
        assertThat(call(as(a,get("/api/v1/me/history")),200).path("total").asInt()).isEqualTo(1);
        assertThat(call(as(b,get("/api/v1/me/history")),200).path("total").asInt()).isZero();
        assertThat(call(as(b,get("/api/v1/me/bookmarks")),200).path("total").asInt()).isEqualTo(2);
    }
    @Test void ownerPublicRouteStillHidesDraftAndDisabledOwnerBecomesUnavailable() throws Exception {
        User owner=user(),reader=user();long published=resource(owner,true);resource(owner,false);
        assertThat(call(as(owner,get("/api/v1/users/"+owner.id()+"/publications")),200).path("total").asInt()).isEqualTo(1);
        assertThat(call(as(owner,get("/api/v1/me/publications")),200).path("total").asInt()).isEqualTo(2);
        call(as(reader,put("/api/v1/resources/"+published+"/bookmark")),200);
        db.update("UPDATE sys_users SET status='DISABLED' WHERE id=?",owner.id());
        call(as(reader,get("/api/v1/users/"+owner.id()+"/profile")),404);
        call(as(reader,get("/api/v1/users/"+owner.id()+"/publications")),404);
        call(as(reader,get("/api/v1/resources/"+published)),404);
        assertThat(call(as(reader,get("/api/v1/me/bookmarks")),200).path("items").get(0).path("summary").isNull()).isTrue();
    }
    @Test void articleRevisionLifecycleNeverReplacesPublishedVersionBeforeReview() throws Exception {
        User owner=user(),reader=user();long id=resource(owner,true);
        db.update("INSERT INTO sys_user_roles(user_id,role_id) SELECT ?,id FROM sys_roles WHERE code='CONTRIBUTOR'",owner.id());sqlSession.clearCache();
        call(as(owner,post("/api/v1/me/articles/"+id+"/revise")),200);
        JsonNode updated=call(as(owner,patch("/api/v1/me/articles/"+id).contentType("application/json").content("{\"title\":\"未公开的新版\",\"body\":\"私有新版正文\"}")),200);
        assertThat(updated.path("title").asText()).isEqualTo("未公开的新版");
        call(as(reader,patch("/api/v1/me/articles/"+id).contentType("application/json").content("{\"title\":\"越权\",\"body\":\"越权\"}")),403);
        call(as(owner,post("/api/v1/me/articles/"+id+"/submit")),200);
        call(as(owner,delete("/api/v1/me/articles/"+id)),409);
        assertThat(call(as(reader,get("/api/v1/resources/"+id)),200).path("title").asText()).isEqualTo("公开标题");
        JsonNode own=call(as(owner,get("/api/v1/me/publications")),200);
        assertThat(own.path("total").asInt()).isEqualTo(1);
        assertThat(own.path("items").get(0).path("revisionState").asText()).isEqualTo("IN_REVIEW");
    }
}
