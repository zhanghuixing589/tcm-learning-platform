-- Candidate names belong to immutable revisions; official names change only on approval.
CREATE TABLE knowledge_revision_names (
 revision_id BIGINT NOT NULL PRIMARY KEY,
 knowledge_type VARCHAR(24) NOT NULL,
 canonical_name VARCHAR(160) NOT NULL,
 CONSTRAINT fk_knowledge_name_revision FOREIGN KEY(revision_id) REFERENCES content_revisions(id) ON DELETE RESTRICT,
 CONSTRAINT ck_candidate_knowledge_type CHECK(knowledge_type IN ('CONCEPT','HERB','FORMULA','EFFECT','SYMPTOM','MERIDIAN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE knowledge_revision_aliases (
 revision_id BIGINT NOT NULL,
 alias VARCHAR(160) NOT NULL,
 PRIMARY KEY(revision_id,alias),
 CONSTRAINT fk_knowledge_alias_revision FOREIGN KEY(revision_id) REFERENCES content_revisions(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO knowledge_revision_names(revision_id,knowledge_type,canonical_name)
 SELECT v.id,k.knowledge_type,k.canonical_name FROM knowledge_entries k JOIN content_revisions v ON v.resource_id=k.resource_id;
INSERT INTO knowledge_revision_aliases(revision_id,alias)
 SELECT v.id,a.alias FROM knowledge_aliases a JOIN content_revisions v ON v.resource_id=a.knowledge_resource_id;
-- NULL official names allow duplicate candidates without reserving unpublished search names.
ALTER TABLE knowledge_entries MODIFY canonical_name VARCHAR(160) NULL COMMENT '仅正式审核名称，草稿为NULL';
UPDATE knowledge_entries k JOIN content_resources r ON r.id=k.resource_id SET k.canonical_name=NULL WHERE r.published_revision_id IS NULL;
CREATE TABLE file_upload_grants (
 file_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(file_id,user_id),
 CONSTRAINT fk_file_grant_file FOREIGN KEY(file_id) REFERENCES file_objects(id) ON DELETE RESTRICT,
 CONSTRAINT fk_file_grant_user FOREIGN KEY(user_id) REFERENCES sys_users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO file_upload_grants(file_id,user_id) SELECT id,uploaded_by FROM file_objects;
