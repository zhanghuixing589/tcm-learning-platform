package cn.tcm.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real MySQL upgrade checks. Every test owns a fresh schema on the worktree's isolated instance. */
class CommunityMigrationTest {
    private String schema;
    private String serverUrl;
    private String url;
    private Connection admin;
    private Connection db;

    @BeforeEach void isolatedSchema() throws Exception {
        Path runtime=Path.of("..", "runtime").toAbsolutePath().normalize().toRealPath();
        JsonNode state=new ObjectMapper().readTree(runtime.resolve("dev-mysql.json").toFile());
        Path scratch=Path.of(state.path("scratch").asText()).toAbsolutePath().normalize().toRealPath();
        assertThat(scratch.startsWith(runtime)).as("MySQL data directory must belong to this worktree").isTrue();
        int port=state.path("port").asInt();
        assertThat(port).isBetween(1024,65535);
        serverUrl="jdbc:mysql://127.0.0.1:"+port+"/";
        String options="?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
        admin=DriverManager.getConnection(serverUrl+options,"root","");
        schema="community_migration_"+UUID.randomUUID().toString().replace("-", "");
        admin.createStatement().execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        url=serverUrl+schema+options;
        db=DriverManager.getConnection(url,"root","");
    }
    @AfterEach void dropOnlyOwnedSchema() throws Exception {
        if(db!=null) db.close();
        if(admin!=null) {
            try {
                if(schema!=null && schema.matches("community_migration_[a-f0-9]{32}"))
                    admin.createStatement().execute("DROP DATABASE `"+schema+"`");
            } finally { admin.close(); }
        }
    }
    private void migrate(String target) {
        var config=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").cleanDisabled(true);
        if(target!=null) config.target(target);
        config.load().migrate();
    }
    private void sql(String query) throws SQLException { try(var st=db.createStatement()){st.execute(query);} }
    private long number(String query) throws SQLException {try(var st=db.createStatement();var rs=st.executeQuery(query)){rs.next();return rs.getLong(1);}}
    private String string(String query) throws SQLException {try(var st=db.createStatement();var rs=st.executeQuery(query)){rs.next();return rs.getString(1);}}
    private boolean columnExists(String table,String column) throws SQLException {
        return number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"+table+"' AND column_name='"+column+"'")==1;
    }
    private void legacyFixture() throws Exception {
        migrate("6");
        sql("INSERT INTO sys_users(id,username,password_hash,nickname) VALUES(901,'migration_owner','unused','Owner')");
        sql("INSERT INTO content_resources(id,resource_type,owner_id) VALUES(910,'POST',901),(911,'ARTICLE',901),(912,'KNOWLEDGE',901)");
        sql("INSERT INTO content_revisions(id,resource_id,version_no,title,body,revision_state,created_by) VALUES(920,910,1,'Old post','  Legacy **plain**  ','APPROVED',901),(921,911,1,'Old article','Article body','IN_REVIEW',901),(922,912,1,'Knowledge','Knowledge body','DRAFT',901)");
        sql("UPDATE content_resources SET published_revision_id=920,visibility_status='PUBLISHED',published_at='2025-01-02 03:04:05.000' WHERE id=910");
        sql("INSERT INTO community_post_profiles(resource_id,section_id) SELECT 910,id FROM community_sections WHERE code='study'");
        sql("INSERT INTO community_comments(id,resource_id,user_id,body,status) VALUES(930,910,901,'  root **plain**  ','VISIBLE'),(931,910,901,'hidden text','HIDDEN'),(932,910,901,'deleted text','DELETED')");
        sql("UPDATE community_comments SET parent_comment_id=930 WHERE id=931");
        sql("UPDATE community_comments SET parent_comment_id=931 WHERE id=932");
        try(var update=db.prepareStatement("UPDATE community_comments SET body=? WHERE id=930")) {
            update.setString(1,"  root\r\n**plain** 😀\r  ");update.executeUpdate();
        }
        try(var update=db.prepareStatement("UPDATE content_revisions SET body=? WHERE id=921")) {
            update.setString(1,"  Article\r\n😀  \r");update.executeUpdate();
        }
    }

    @Test void emptySchemaCreatesElevenTablesPolicyContractAndOnlyExplicitAdminCapabilities() throws Exception {
        migrate(null);
        assertThat(number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'")).isEqualTo(84);
        assertThat(number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")).isEqualTo(9);
        assertThat(columnExists("community_comments","body")).isFalse();
        JsonNode policy=new ObjectMapper().readTree(string("SELECT config FROM community_text_policies WHERE version_no=1"));
        assertThat(policy.path("maxTitleChars").asInt()).isEqualTo(120);
        assertThat(policy.path("maxArticleHardChars").asInt()).isEqualTo(500000);
        assertThat(policy.path("maxRequestBytes").asInt()).isEqualTo(8388608);
        assertThat(policy.path("maxPostChars").asInt()).isEqualTo(20000);
        assertThat(policy.path("maxArticleChars").asInt()).isEqualTo(100000);
        assertThat(policy.path("maxCommentChars").asInt()).isEqualTo(2000);
        assertThat(policy.path("maxReferences").asInt()).isEqualTo(300);
        assertThat(policy.path("maxReferenceChars").asInt()).isEqualTo(200000);
        assertThat(policy.path("allowedSyntax").toString()).isEqualTo("[\"paragraph\",\"heading\",\"emphasis\",\"strong\",\"blockquote\",\"code\",\"codeBlock\",\"list\",\"link\",\"table\",\"hr\"]");
        assertThat(policy.path("allowedProtocols").toString()).isEqualTo("[\"http\",\"https\"]");
        assertThat(policy.path("rates")).isEqualTo(new ObjectMapper().readTree("""
                {"POST_CREATE":[{"seconds":60,"limit":3},{"seconds":3600,"limit":20}],
                 "COMMENT_CREATE":[{"seconds":60,"limit":10},{"seconds":3600,"limit":120}],
                 "PUBLIC_EDIT":[{"seconds":60,"limit":6},{"seconds":3600,"limit":60}],
                 "DRAFT_SAVE":[{"seconds":60,"limit":30},{"seconds":3600,"limit":300}],
                 "QUOTA_REQUEST":[{"seconds":86400,"limit":3}]}
                """));
        assertThat(policy.path("rates").path("POST_CREATE").toString()).isEqualTo("[{\"limit\":3,\"seconds\":60},{\"limit\":20,\"seconds\":3600}]");
        assertThat(policy.path("rates").path("COMMENT_CREATE").get(1).path("limit").asInt()).isEqualTo(120);
        assertThat(policy.path("rates").path("PUBLIC_EDIT").get(1).path("limit").asInt()).isEqualTo(60);
        assertThat(policy.path("rates").path("DRAFT_SAVE").get(1).path("limit").asInt()).isEqualTo(300);
        assertThat(policy.path("rates").path("QUOTA_REQUEST").get(0).path("seconds").asInt()).isEqualTo(86400);
        assertThat(number("SELECT COUNT(*) FROM sys_role_permissions rp JOIN sys_roles r ON r.id=rp.role_id JOIN sys_permissions p ON p.id=rp.permission_id WHERE r.code='ADMIN' AND p.code IN ('community:section:manage','community:quota:manage')")).isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM sys_role_permissions rp JOIN sys_roles r ON r.id=rp.role_id JOIN sys_permissions p ON p.id=rp.permission_id WHERE r.code='ADMIN' AND p.code IN ('knowledge:review','community:moderate')")).isZero();
    }
    @Test void populatedV6UpgradePreservesIdentityBodyStatusAndLegacySources() throws Exception {
        legacyFixture();
        long section=number("SELECT section_id FROM community_post_profiles WHERE resource_id=910");
        migrate(null);
        assertThat(number("SELECT COUNT(*) FROM content_resources WHERE id IN(910,911,912)")).isEqualTo(3);
        assertThat(string("SELECT body FROM content_revisions WHERE id=920")).isEqualTo("  Legacy **plain**  ");
        assertThat(string("SELECT revision_state FROM content_revisions WHERE id=921")).isEqualTo("IN_REVIEW");
        assertThat(number("SELECT section_id FROM community_post_profiles WHERE resource_id=910")).isEqualTo(section);
        assertThat(number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='community_revision_profiles'")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM community_revision_profiles WHERE source_format='PLAIN_TEXT' AND release_method='LEGACY'")).isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM community_revision_profiles WHERE revision_id=922")).isZero();
        assertThat(string("SELECT body FROM content_revisions WHERE id=922")).isEqualTo("Knowledge body");
        assertThat(number("SELECT COUNT(*) FROM community_comment_revisions WHERE release_method='LEGACY'")).isEqualTo(3);
        assertThat(string("SELECT body FROM community_comment_revisions WHERE comment_id=931")).isEqualTo("hidden text");
        assertThat(string("SELECT body FROM community_comment_revisions WHERE comment_id=930")).isEqualTo("  root\r\n**plain** 😀\r  ");
        assertThat(string("SELECT source_sha256 FROM community_comment_revisions WHERE comment_id=930")).isEqualTo("9ef558decf7b529723f95bf4f3acd4c44007a61e1321133249f5477a6277280b");
        assertThat(number("SELECT body_char_count FROM community_comment_revisions WHERE comment_id=930")).isEqualTo(21);
        assertThat(string("SELECT body FROM content_revisions WHERE id=921")).isEqualTo("  Article\r\n😀  \r");
        assertThat(number("SELECT body_char_count FROM community_revision_profiles WHERE revision_id=921")).isEqualTo(14);
        assertThat(string("SELECT body FROM community_comment_revisions WHERE comment_id=932")).isEqualTo("deleted text");
        assertThat(string("SELECT status FROM community_comments WHERE id=931")).isEqualTo("HIDDEN");
        assertThat(string("SELECT status FROM community_comments WHERE id=932")).isEqualTo("DELETED");
        assertThat(number("SELECT published_revision_id FROM community_comments WHERE id=930")).isPositive();
        assertThat(number("SELECT COUNT(*) FROM community_comments WHERE id IN(931,932) AND published_revision_id IS NULL AND candidate_revision_id IS NULL")).isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM community_post_profiles p JOIN community_sections s ON s.id=p.section_id WHERE p.resource_id=911 AND s.code='legacy-content' AND s.active=0 AND s.lifecycle='FORMAL' AND s.owner_id IS NULL")).isEqualTo(1);
        assertThat(string("SELECT CAST(published_at AS CHAR) FROM content_resources WHERE id=910")).startsWith("2025-01-02 03:04:05");
        assertThat(string("SELECT CAST(public_edited_at AS CHAR) FROM community_post_profiles WHERE resource_id=910")).startsWith("2025-01-02 03:04:05");
        rootAndNestedReplyBackfillKeepsActualReplyTarget();
        compositeCommentRevisionForeignKeysRejectOtherCommentsVersions();
        onlyOnePendingQuotaPerResourceAndOnlyOnePolicyHead();
        assertThat(string("SELECT source_sha256 FROM community_revision_profiles WHERE revision_id=920"))
                .isEqualTo("e6b3568113428609af05f655cad61dd0b2b93ce8fe62089f13cdad0adadd62cc");
        assertThat(number("SELECT COUNT(*) FROM sys_role_permissions rp JOIN sys_roles r ON r.id=rp.role_id JOIN sys_permissions p ON p.id=rp.permission_id WHERE r.code='ADMIN' AND p.code IN('community:section:manage','community:quota:manage')")).isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM sys_role_permissions rp JOIN sys_roles r ON r.id=rp.role_id JOIN sys_permissions p ON p.id=rp.permission_id WHERE r.code='ADMIN' AND p.code IN('knowledge:review','community:moderate')")).isZero();
    }
    private void rootAndNestedReplyBackfillKeepsActualReplyTarget() throws Exception {
        assertThat(number("SELECT COUNT(*) FROM community_comments WHERE id=930 AND root_comment_id IS NULL AND parent_comment_id IS NULL")).isEqualTo(1);
        assertThat(number("SELECT root_comment_id FROM community_comments WHERE id=931")).isEqualTo(930);
        assertThat(number("SELECT root_comment_id FROM community_comments WHERE id=932")).isEqualTo(930);
        assertThat(number("SELECT parent_comment_id FROM community_comments WHERE id=932")).isEqualTo(931);
    }
    @Test void normalizedSiblingCollisionFailsBeforeAnyDestructiveWrite() throws Exception {
        migrate("6");
        sql("INSERT INTO community_sections(code,name) VALUES('collision-a','ＡＢＣ  Study'),('collision-b','\u0085abc　study\u0085')");
        assertThatThrownBy(()->migrate(null)).hasStackTraceContaining("normalized").hasStackTraceContaining("collision");
        assertThat(number("SELECT COUNT(*) FROM community_sections WHERE code IN('collision-a','collision-b')")).isEqualTo(2);
        assertThat(columnExists("community_sections","normalized_name")).isFalse();
        assertThat(columnExists("community_comments","body")).isTrue();
    }
    @Test void reservedHistoricalCodeUsesExistingDatabaseCollationBeforeAnyDdl() throws Exception {
        legacyFixture();
        sql("INSERT INTO community_sections(code,name) VALUES('légacy-content','Existing unrelated section')");
        assertThatThrownBy(()->migrate(null)).hasStackTraceContaining("historical container collision");
        assertThat(columnExists("community_sections","normalized_name")).isFalse();
        assertThat(columnExists("community_comments","body")).isTrue();
        assertThat(number("SELECT COUNT(*) FROM community_sections WHERE code='légacy-content' AND name='Existing unrelated section'")).isEqualTo(1);
    }
    @Test void cyclicLegacyReplyFailsBeforeRemovingOrOverwritingBodies() throws Exception {
        legacyFixture();
        sql("UPDATE community_comments SET parent_comment_id=932 WHERE id=930");
        assertThatThrownBy(()->migrate(null)).hasStackTraceContaining("comment").hasStackTraceContaining("cycle");
        assertThat(columnExists("community_comments","body")).isTrue();
        assertThat(string("SELECT body FROM community_comments WHERE id=932")).isEqualTo("deleted text");
    }
    @Test void crossResourceLegacyReplyFailsBeforeRemovingBodies() throws Exception {
        legacyFixture();
        sql("INSERT INTO community_post_profiles(resource_id,section_id) SELECT 911,id FROM community_sections WHERE code='study'");
        sql("SET FOREIGN_KEY_CHECKS=0");
        try {sql("UPDATE community_comments SET resource_id=911 WHERE id=931");} finally {sql("SET FOREIGN_KEY_CHECKS=1");}
        assertThatThrownBy(()->migrate(null)).hasStackTraceContaining("comment").hasStackTraceContaining("resource");
        assertThat(columnExists("community_comments","body")).isTrue();
    }
    private void compositeCommentRevisionForeignKeysRejectOtherCommentsVersions() throws Exception {
        long other=number("SELECT id FROM community_comment_revisions WHERE comment_id=931");
        assertThatThrownBy(()->sql("UPDATE community_comments SET published_revision_id="+other+" WHERE id=930")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->sql("UPDATE community_comments SET candidate_revision_id="+other+" WHERE id=930")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->sql("INSERT INTO community_comment_revisions(comment_id,version_no,base_revision_id,body,revision_state,release_method,format_policy_id,source_sha256,body_char_count,created_by) SELECT 930,2,"+other+",'bad','DRAFT','NONE',id,REPEAT('0',64),3,901 FROM community_text_policies LIMIT 1")).isInstanceOf(SQLException.class);
    }
    private void onlyOnePendingQuotaPerResourceAndOnlyOnePolicyHead() throws Exception {
        sql("INSERT INTO community_quota_requests(resource_id,requested_by,requested_limit,reason,status,lock_version) VALUES(911,901,200000,'Need more','PENDING',0)");
        assertThatThrownBy(()->sql("INSERT INTO community_quota_requests(resource_id,requested_by,requested_limit,reason,status,lock_version) VALUES(911,901,250000,'Again','PENDING',0)")).isInstanceOf(SQLException.class);
        sql("UPDATE community_quota_requests SET status='REJECTED' WHERE resource_id=911");
        sql("INSERT INTO community_quota_requests(resource_id,requested_by,requested_limit,reason,status,lock_version) VALUES(911,901,250000,'Retry','PENDING',0)");
        assertThatThrownBy(()->sql("INSERT INTO community_text_policy_heads(id,policy_id,lock_version) SELECT 2,id,0 FROM community_text_policies LIMIT 1")).isInstanceOf(SQLException.class);
        assertThat(number("SELECT COUNT(*) FROM community_text_policy_heads")).isEqualTo(1);
    }
}
