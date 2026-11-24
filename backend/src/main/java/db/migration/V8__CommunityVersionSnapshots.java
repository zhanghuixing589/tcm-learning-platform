package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Copies legacy bytes before removing the old comment body, with independent integrity verification. */
public class V8__CommunityVersionSnapshots extends BaseJavaMigration {
    private record Comment(long id,long resourceId,Long parentId) {}
    private static final String POLICY="""
            {"allowedSyntax":["paragraph","heading","emphasis","strong","blockquote","code","codeBlock","list","link","table","hr"],
             "allowedProtocols":["http","https"],"maxTitleChars":120,"maxPostChars":20000,"maxArticleChars":100000,
             "maxCommentChars":2000,"maxReferences":300,"maxReferenceChars":200000,"maxArticleHardChars":500000,
             "maxRequestBytes":8388608,
             "rates":{"POST_CREATE":[{"seconds":60,"limit":3},{"seconds":3600,"limit":20}],
                      "COMMENT_CREATE":[{"seconds":60,"limit":10},{"seconds":3600,"limit":120}],
                      "PUBLIC_EDIT":[{"seconds":60,"limit":6},{"seconds":3600,"limit":60}],
                      "DRAFT_SAVE":[{"seconds":60,"limit":30},{"seconds":3600,"limit":300}],
                      "QUOTA_REQUEST":[{"seconds":86400,"limit":3}]}}
            """;
    @Override public void migrate(Context context) throws Exception {
        Connection db=context.getConnection();
        Map<Long,Comment> comments=new HashMap<>();
        try(var st=db.createStatement();var rs=st.executeQuery("SELECT id,resource_id,parent_comment_id FROM community_comments ORDER BY id")) {
            while(rs.next()) {
                long parent=rs.getLong("parent_comment_id");
                Long nullableParent=rs.wasNull()?null:parent;
                comments.put(rs.getLong("id"),new Comment(rs.getLong("id"),rs.getLong("resource_id"),nullableParent));
            }
        }
        // All relation checks precede any V8 DDL/DML. MySQL DDL cannot be rolled back.
        Map<Long,Long> roots=resolveRoots(comments);
        execute(db,"""
                CREATE TABLE community_text_policies (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,version_no INT NOT NULL,config JSON NOT NULL,
                  created_by BIGINT NULL,reason VARCHAR(1000) NOT NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  UNIQUE KEY uk_text_policy_version(version_no),
                  CONSTRAINT fk_text_policy_creator FOREIGN KEY(created_by) REFERENCES sys_users(id),
                  CONSTRAINT ck_text_policy_version CHECK(version_no>0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                ""","""
                CREATE TABLE community_text_policy_heads (
                  id BIGINT NOT NULL PRIMARY KEY,policy_id BIGINT NOT NULL,lock_version INT NOT NULL DEFAULT 0,
                  updated_by BIGINT NULL,updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  CONSTRAINT ck_text_policy_single_head CHECK(id=1),CONSTRAINT ck_text_policy_head_lock CHECK(lock_version>=0),
                  CONSTRAINT fk_text_policy_head_policy FOREIGN KEY(policy_id) REFERENCES community_text_policies(id),
                  CONSTRAINT fk_text_policy_head_actor FOREIGN KEY(updated_by) REFERENCES sys_users(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                ""","""
                CREATE TABLE community_revision_profiles (
                  revision_id BIGINT NOT NULL PRIMARY KEY,resource_id BIGINT NOT NULL,section_id BIGINT NOT NULL,
                  source_format VARCHAR(16) NOT NULL,format_policy_id BIGINT NOT NULL,release_method VARCHAR(20) NOT NULL,
                  source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,body_char_count INT NOT NULL,
                  lock_version INT NOT NULL DEFAULT 0,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  KEY ix_community_revision_resource(resource_id,revision_id),KEY ix_community_revision_section(section_id,revision_id),
                  CONSTRAINT fk_community_revision FOREIGN KEY(resource_id,revision_id) REFERENCES content_revisions(resource_id,id),
                  CONSTRAINT fk_community_revision_section FOREIGN KEY(section_id) REFERENCES community_sections(id),
                  CONSTRAINT fk_community_revision_policy FOREIGN KEY(format_policy_id) REFERENCES community_text_policies(id),
                  CONSTRAINT ck_community_source_format CHECK(source_format IN('PLAIN_TEXT','MARKDOWN')),
                  CONSTRAINT ck_community_release CHECK(release_method IN('NONE','DIRECT','MANUAL_REVIEW','LEGACY')),
                  CONSTRAINT ck_community_revision_count CHECK(body_char_count>=0),CONSTRAINT ck_community_revision_lock CHECK(lock_version>=0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                ""","""
                CREATE TABLE community_revision_references (
                  revision_id BIGINT NOT NULL,reference_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                  display_number INT NOT NULL,title VARCHAR(500) NOT NULL,authors VARCHAR(1000) NULL,publication_year SMALLINT NULL,
                  source_name VARCHAR(1000) NULL,doi VARCHAR(255) NULL,url VARCHAR(2048) NULL,location_label VARCHAR(500) NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(revision_id,reference_key),UNIQUE KEY uk_revision_reference_number(revision_id,display_number),
                  CONSTRAINT fk_community_reference_revision FOREIGN KEY(revision_id) REFERENCES content_revisions(id),
                  CONSTRAINT ck_reference_display_number CHECK(display_number>0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                ""","""
                CREATE TABLE community_comment_revisions (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,comment_id BIGINT NOT NULL,version_no INT NOT NULL,
                  base_revision_id BIGINT NULL,body TEXT NOT NULL,revision_state VARCHAR(20) NOT NULL,release_method VARCHAR(20) NOT NULL,
                  format_policy_id BIGINT NOT NULL,source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                  body_char_count INT NOT NULL,created_by BIGINT NOT NULL,submitted_at DATETIME(3) NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  UNIQUE KEY uk_comment_version(comment_id,version_no),UNIQUE KEY uk_comment_revision_identity(comment_id,id),
                  CONSTRAINT fk_comment_revision_comment FOREIGN KEY(comment_id) REFERENCES community_comments(id),
                  CONSTRAINT fk_comment_revision_base FOREIGN KEY(comment_id,base_revision_id) REFERENCES community_comment_revisions(comment_id,id),
                  CONSTRAINT fk_comment_revision_policy FOREIGN KEY(format_policy_id) REFERENCES community_text_policies(id),
                  CONSTRAINT fk_comment_revision_creator FOREIGN KEY(created_by) REFERENCES sys_users(id),
                  CONSTRAINT ck_comment_version CHECK(version_no>0),CONSTRAINT ck_comment_body_count CHECK(body_char_count>=0),
                  CONSTRAINT ck_comment_revision_state CHECK(revision_state IN('DRAFT','IN_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
                  CONSTRAINT ck_comment_release CHECK(release_method IN('NONE','DIRECT','MANUAL_REVIEW','LEGACY'))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                ""","""
                ALTER TABLE community_comments ADD root_comment_id BIGINT NULL,ADD published_revision_id BIGINT NULL,
                  ADD candidate_revision_id BIGINT NULL,ADD lock_version INT NOT NULL DEFAULT 0,
                  ADD CONSTRAINT ck_comment_lock CHECK(lock_version>=0),
                  ADD KEY ix_comment_thread(resource_id,root_comment_id,status,created_at,id)
                """);
        try(var insert=db.prepareStatement("INSERT INTO community_text_policies(id,version_no,config,reason) VALUES(1,1,?,'Initial community text policy')")) {
            insert.setString(1,POLICY);insert.executeUpdate();
        }
        execute(db,"INSERT INTO community_text_policy_heads(id,policy_id) VALUES(1,1)");
        ObjectMapper json=new ObjectMapper();
        try(var st=db.createStatement();var rs=st.executeQuery("SELECT v.id,v.resource_id,v.title,v.body,v.created_at,v.updated_at,p.section_id FROM content_revisions v JOIN content_resources r ON r.id=v.resource_id JOIN community_post_profiles p ON p.resource_id=r.id WHERE r.resource_type IN('POST','ARTICLE') ORDER BY v.id");
            var insert=db.prepareStatement("INSERT INTO community_revision_profiles(revision_id,resource_id,section_id,source_format,format_policy_id,release_method,source_sha256,body_char_count,created_at,updated_at) VALUES(?,?,?,'PLAIN_TEXT',1,'LEGACY',?,?,?,?)")) {
            while(rs.next()) {
                String body=rs.getString("body");
                Map<String,Object> canonical=new TreeMap<>();
                canonical.put("body",normalizeLines(body));canonical.put("references",List.of());
                canonical.put("sectionId",Long.toString(rs.getLong("section_id")));canonical.put("sourceFormat","PLAIN_TEXT");canonical.put("title",rs.getString("title"));
                insert.setLong(1,rs.getLong("id"));insert.setLong(2,rs.getLong("resource_id"));insert.setLong(3,rs.getLong("section_id"));
                insert.setString(4,sha256(json.writeValueAsString(canonical)));insert.setInt(5,normalizeLines(body).codePointCount(0,normalizeLines(body).length()));
                insert.setTimestamp(6,rs.getTimestamp("created_at"));insert.setTimestamp(7,rs.getTimestamp("updated_at"));insert.executeUpdate();
            }
        }
        try(var st=db.createStatement();var rs=st.executeQuery("SELECT id,user_id,body,created_at,updated_at FROM community_comments ORDER BY id");
            var insert=db.prepareStatement("INSERT INTO community_comment_revisions(comment_id,version_no,body,revision_state,release_method,format_policy_id,source_sha256,body_char_count,created_by,created_at,updated_at) VALUES(?,1,?,'APPROVED','LEGACY',1,?,?,?,?,?)")) {
            while(rs.next()) {
                String body=rs.getString("body"),normalized=normalizeLines(body);
                insert.setLong(1,rs.getLong("id"));insert.setString(2,body);insert.setString(3,sha256(normalized));
                insert.setInt(4,normalized.codePointCount(0,normalized.length()));insert.setLong(5,rs.getLong("user_id"));
                insert.setTimestamp(6,rs.getTimestamp("created_at"));insert.setTimestamp(7,rs.getTimestamp("updated_at"));insert.executeUpdate();
            }
        }
        try(var update=db.prepareStatement("UPDATE community_comments SET root_comment_id=?,updated_at=updated_at WHERE id=?")) {
            for(Comment comment:comments.values()) if(comment.parentId()!=null) {
                update.setLong(1,roots.get(comment.id()));update.setLong(2,comment.id());update.executeUpdate();
            }
        }
        execute(db,"""
                UPDATE community_comments c JOIN community_comment_revisions v ON v.comment_id=c.id AND v.version_no=1
                  SET c.published_revision_id=CASE WHEN c.status='VISIBLE' THEN v.id ELSE NULL END,c.updated_at=c.updated_at
                """);
        long verified=0;
        try(var st=db.createStatement();var rs=st.executeQuery("SELECT c.id,c.body old_body,v.body snapshot_body,v.source_sha256 FROM community_comments c JOIN community_comment_revisions v ON v.comment_id=c.id AND v.version_no=1")) {
            while(rs.next()) {
                String original=rs.getString("old_body"),snapshot=rs.getString("snapshot_body");
                if(!sha256(original).equals(sha256(snapshot)) || !sha256(normalizeLines(original)).equals(rs.getString("source_sha256")))
                    throw new SQLException("comment snapshot body hash mismatch; repair comment id="+rs.getLong("id")+"; old body retained");
                verified++;
            }
        }
        if(verified!=comments.size()) throw new SQLException("comment snapshot count mismatch; old body retained");
        execute(db,"""
                ALTER TABLE community_comments
                  ADD CONSTRAINT fk_comment_root FOREIGN KEY(resource_id,root_comment_id) REFERENCES community_comments(resource_id,id),
                  ADD CONSTRAINT fk_comment_public_revision FOREIGN KEY(id,published_revision_id) REFERENCES community_comment_revisions(comment_id,id),
                  ADD CONSTRAINT fk_comment_candidate_revision FOREIGN KEY(id,candidate_revision_id) REFERENCES community_comment_revisions(comment_id,id)
                ""","ALTER TABLE community_comments DROP COLUMN body");
    }
    private static Map<Long,Long> resolveRoots(Map<Long,Comment> comments) throws SQLException {
        Map<Long,Long> roots=new HashMap<>();
        for(Comment comment:comments.values()) {
            Set<Long> path=new LinkedHashSet<>();
            Comment cursor=comment;
            while(true) {
                if(!path.add(cursor.id())) throw new SQLException("comment cycle; repair comment ids="+path+" before upgrade");
                if(roots.containsKey(cursor.id())) {long root=roots.get(cursor.id());for(long id:path) roots.put(id,root);break;}
                if(cursor.parentId()==null) {for(long id:path) roots.put(id,cursor.id());break;}
                Comment parent=comments.get(cursor.parentId());
                if(parent==null) throw new SQLException("comment missing parent; repair comment id="+cursor.id()+",parent="+cursor.parentId());
                if(parent.resourceId()!=cursor.resourceId()) throw new SQLException("comment resource mismatch; repair comment ids="+cursor.id()+","+parent.id());
                cursor=parent;
            }
        }
        return roots;
    }
    private static String normalizeLines(String source) {return source.replace("\r\n","\n").replace('\r','\n');}
    private static String sha256(String source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    }
    private static void execute(Connection db,String... statements) throws SQLException {
        for(String sql:statements) try(var st=db.createStatement()){st.execute(sql);}
    }
}
