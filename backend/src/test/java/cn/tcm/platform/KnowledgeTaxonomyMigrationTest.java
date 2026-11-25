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
class KnowledgeTaxonomyMigrationTest {
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
        schema="taxonomy_migration_"+UUID.randomUUID().toString().replace("-", "");
        admin.createStatement().execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        url=serverUrl+schema+options;
        db=DriverManager.getConnection(url,"root","");
    }
    @AfterEach void dropOnlyOwnedSchema() throws Exception {
        if(db!=null) db.close();
        if(admin!=null) {
            try {
                if(schema!=null && schema.matches("taxonomy_migration_[a-f0-9]{32}"))
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

    @Test void upgradesLegacyReferencesWithoutRewritingKnowledgeOrTags() throws Exception {
        migrate("9");
        sql("INSERT INTO sys_users(id,username,password_hash,nickname) VALUES(800001,'taxonomy_migration_user','unused','迁移测试')");
        sql("INSERT INTO content_resources(id,resource_type,owner_id) VALUES(800001,'KNOWLEDGE',800001)");
        sql("INSERT INTO knowledge_entries(resource_id,knowledge_type) VALUES(800001,'HERB')");
        sql("INSERT INTO content_revisions(id,resource_id,version_no,title,body,category_id,created_by) SELECT 800001,800001,1,'原版本','保留原正文',id,800001 FROM content_categories WHERE code='tcm-concept'");
        sql("INSERT INTO knowledge_revision_names VALUES(800001,'HERB','原名称')");
        sql("INSERT INTO content_tags(id,name) VALUES(800001,'跨类型旧标签')");
        sql("INSERT INTO revision_tags(revision_id,tag_id) VALUES(800001,800001)");
        long category=number("SELECT category_id FROM content_revisions WHERE id=800001");
        migrate(null);
        assertThat(number("SELECT COUNT(*) FROM content_categories WHERE legacy_archive=0 AND scope='KNOWLEDGE'")).isEqualTo(31);
        assertThat(number("SELECT legacy_archive FROM content_categories WHERE id="+category)).isEqualTo(1);
        assertThat(number("SELECT active FROM content_categories WHERE id="+category)).isZero();
        assertThat(number("SELECT category_id FROM content_revisions WHERE id=800001")).isEqualTo(category);
        assertThat(string("SELECT body FROM content_revisions WHERE id=800001")).isEqualTo("保留原正文");
        assertThat(number("SELECT COUNT(*) FROM revision_tags WHERE revision_id=800001 AND tag_id=800001")).isEqualTo(1);
        long wrong=number("SELECT id FROM content_categories WHERE knowledge_type='FORMULA' LIMIT 1");
        assertThatThrownBy(()->sql("UPDATE content_revisions SET category_id="+wrong+" WHERE id=800001")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->sql("UPDATE content_categories SET active=1 WHERE id="+category)).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->sql("INSERT INTO content_categories(code,name,scope) VALUES('bad-untyped','无类型新目录','KNOWLEDGE')")).isInstanceOf(SQLException.class);
    }
}
