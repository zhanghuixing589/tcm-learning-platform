package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** V6 -> community foundations. All data preflight runs before MySQL's nontransactional DDL. */
public class V7__CommunityFoundation extends BaseJavaMigration {
    private record Section(long id, String normalized) {}
    @Override public void migrate(Context context) throws Exception {
        Connection db=context.getConnection();
        List<Section> sections=new ArrayList<>();
        Map<String,Long> names=new HashMap<>();
        try(var st=db.createStatement();var rs=st.executeQuery("SELECT id,name,code FROM community_sections ORDER BY id")) {
            while(rs.next()) {
                long id=rs.getLong("id");
                String normalized=Normalizer.normalize(rs.getString("name"),Normalizer.Form.NFKC)
                        .replaceAll("(?U)\\s+"," ").strip().toLowerCase(Locale.ROOT);
                if(normalized.isBlank() || normalized.codePointCount(0,normalized.length())>160)
                    throw new SQLException("Invalid normalized section name; repair section id="+id+" before upgrade");
                Long previous=names.putIfAbsent(normalized,id);
                if(previous!=null) throw new SQLException("normalized section name collision; repair section ids="+previous+","+id+" before upgrade");
                sections.add(new Section(id,normalized));
            }
        }
        boolean needsLegacy;
        try(var st=db.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM content_resources r LEFT JOIN community_post_profiles p ON p.resource_id=r.id WHERE r.resource_type IN('POST','ARTICLE') AND p.id IS NULL")) {
            rs.next();needsLegacy=rs.getLong(1)>0;
        }
        Long reservedCodeId=null;
        // Match the existing code unique key's actual collation, including accent equivalence.
        if(needsLegacy) try(var st=db.createStatement();var rs=st.executeQuery("SELECT id FROM community_sections WHERE code='legacy-content'")) {
            if(rs.next()) reservedCodeId=rs.getLong(1);
        }
        if(needsLegacy && (reservedCodeId!=null || names.containsKey("历史内容")))
            throw new SQLException("normalized historical container collision; existing section id="
                    +(reservedCodeId!=null?reservedCodeId:names.get("历史内容"))
                    +" reserves code/name legacy-content/历史内容; repair before upgrade");

        execute(db,"""
                ALTER TABLE community_sections
                  ADD parent_id BIGINT NULL,
                  ADD normalized_name VARCHAR(160) COLLATE utf8mb4_0900_bin NULL,
                  ADD sibling_scope BIGINT GENERATED ALWAYS AS (IFNULL(parent_id,0)) STORED,
                  ADD created_by BIGINT NULL, ADD owner_id BIGINT NULL,
                  ADD lifecycle VARCHAR(16) NOT NULL DEFAULT 'FORMAL',
                  ADD management_status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                  ADD rules_text TEXT NULL, ADD lock_version INT NOT NULL DEFAULT 0,
                  ADD CONSTRAINT fk_section_parent FOREIGN KEY(parent_id) REFERENCES community_sections(id),
                  ADD CONSTRAINT fk_section_creator FOREIGN KEY(created_by) REFERENCES sys_users(id),
                  ADD CONSTRAINT fk_section_owner FOREIGN KEY(owner_id) REFERENCES sys_users(id),
                  ADD CONSTRAINT ck_section_lifecycle CHECK(lifecycle IN('OBSERVING','FORMAL','ARCHIVED')),
                  ADD CONSTRAINT ck_section_management CHECK(management_status IN('NORMAL','UNOWNED')),
                  ADD CONSTRAINT ck_section_lock CHECK(lock_version>=0),
                  ADD KEY ix_section_parent(parent_id,active,lifecycle,sort_order,id),
                  ADD KEY ix_section_owner(owner_id,lifecycle,id)
                """);
        // MySQL ERROR 3818 forbids CHECK(parent_id<>id) because id is AUTO_INCREMENT.
        // Self-parent and maximum depth are validated by the section command service under parent locks.
        try(var update=db.prepareStatement("UPDATE community_sections SET normalized_name=?,updated_at=updated_at WHERE id=?")) {
            for(Section section:sections) {update.setString(1,section.normalized());update.setLong(2,section.id());update.executeUpdate();}
        }
        execute(db,"ALTER TABLE community_sections MODIFY normalized_name VARCHAR(160) COLLATE utf8mb4_0900_bin NOT NULL, ADD UNIQUE KEY uk_section_sibling(sibling_scope,normalized_name)","""
                CREATE TABLE community_section_members (
                  section_id BIGINT NOT NULL,user_id BIGINT NOT NULL,state VARCHAR(12) NOT NULL,
                  joined_at DATETIME(3) NOT NULL,left_at DATETIME(3) NULL,reason_code VARCHAR(40) NULL,
                  lock_version INT NOT NULL DEFAULT 0,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(section_id,user_id),KEY ix_member_user(user_id,state,section_id),KEY ix_member_section(section_id,state,user_id),
                  CONSTRAINT fk_member_section FOREIGN KEY(section_id) REFERENCES community_sections(id),
                  CONSTRAINT fk_member_user FOREIGN KEY(user_id) REFERENCES sys_users(id),
                  CONSTRAINT ck_member_state CHECK(state IN('JOINED','LEFT','REMOVED')),
                  CONSTRAINT ck_member_lock CHECK(lock_version>=0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                ""","""
                CREATE TABLE community_section_events (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,section_id BIGINT NOT NULL,
                  subject_user_id BIGINT NULL,actor_id BIGINT NULL,action VARCHAR(40) NOT NULL,reason_code VARCHAR(40) NULL,
                  before_value JSON NULL,after_value JSON NULL,event_key VARCHAR(160) COLLATE utf8mb4_0900_bin NOT NULL,trace_id VARCHAR(64) NOT NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  UNIQUE KEY uk_section_event(event_key),KEY ix_section_event(section_id,id),KEY ix_section_event_subject(subject_user_id,id),
                  CONSTRAINT fk_section_event_section FOREIGN KEY(section_id) REFERENCES community_sections(id),
                  CONSTRAINT fk_section_event_subject FOREIGN KEY(subject_user_id) REFERENCES sys_users(id),
                  CONSTRAINT fk_section_event_actor FOREIGN KEY(actor_id) REFERENCES sys_users(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                ""","""
                ALTER TABLE community_post_profiles
                  ADD public_edited_at DATETIME(3) NULL,ADD last_public_comment_at DATETIME(3) NULL,
                  ADD lock_version INT NOT NULL DEFAULT 0,
                  ADD CONSTRAINT ck_post_profile_lock CHECK(lock_version>=0),
                  ADD KEY ix_post_public_edit(section_id,is_pinned,public_edited_at,resource_id),
                  ADD KEY ix_post_public_comment(section_id,last_public_comment_at,resource_id)
                ""","INSERT INTO sys_permissions(code,name) VALUES('community:section:manage','分区基础管理'),('community:quota:manage','文章额度管理')", """
                INSERT INTO sys_role_permissions(role_id,permission_id)
                SELECT r.id,p.id FROM sys_roles r CROSS JOIN sys_permissions p
                WHERE r.code='ADMIN' AND p.code IN('community:section:manage','community:quota:manage')
                """);
        if(needsLegacy) execute(db,"""
                INSERT INTO community_sections(code,name,normalized_name,description,active,lifecycle)
                  VALUES('legacy-content','历史内容','历史内容','历史未分区内容容器；隐藏导航且不接受新建内容',0,'FORMAL')
                ""","""
                INSERT INTO community_post_profiles(resource_id,section_id,created_at,updated_at)
                SELECT r.id,s.id,r.created_at,r.updated_at FROM content_resources r
                CROSS JOIN community_sections s LEFT JOIN community_post_profiles p ON p.resource_id=r.id
                WHERE s.code='legacy-content' AND r.resource_type IN('POST','ARTICLE') AND p.id IS NULL
                """);
    }
    private static void execute(Connection db,String... statements) throws SQLException {
        for(String sql:statements) try(var st=db.createStatement()){st.execute(sql);}
    }
}
