-- Append-only human reading snapshots, source rights and independent knowledge proposals.
-- Existing file IDs, parse runs, segments, published revisions and quotes are retained.
ALTER TABLE file_objects
  ADD COLUMN storage_backend VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
  ADD COLUMN storage_bucket VARCHAR(128) NULL,
  ADD CONSTRAINT ck_source_storage_backend CHECK (storage_backend IN ('LOCAL','MINIO'));

ALTER TABLE source_documents
  ADD COLUMN lock_version INT NOT NULL DEFAULT 0,
  ADD COLUMN rights_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNCONFIRMED',
  ADD COLUMN license_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN copyright_source TEXT NULL,
  ADD COLUMN authorization_scope TEXT NULL,
  ADD COLUMN public_reading_allowed BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN public_download_allowed BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN quote_display_allowed BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN rag_use_allowed BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN reader_required BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN approved_use VARCHAR(24) NOT NULL DEFAULT 'TEXT_CITABLE',
  ADD COLUMN approved_coverage JSON NULL,
  ADD COLUMN acknowledged_warnings JSON NULL,
  ADD COLUMN active_reading_edition_id BIGINT NULL,
  ADD COLUMN reviewed_parse_run_id BIGINT NULL,
  ADD COLUMN reviewed_reading_edition_id BIGINT NULL,
  ADD CONSTRAINT ck_source_rights_status CHECK (rights_status IN ('LEGACY_UNCONFIRMED','UNCONFIRMED','DECLARED')),
  ADD CONSTRAINT ck_source_rights_version CHECK (lock_version >= 0),
  ADD CONSTRAINT ck_source_rights_license CHECK (license_type IN ('UNKNOWN','OWNED','AUTHORIZED','PUBLIC_DOMAIN','OPEN_LICENSE'));

ALTER TABLE document_parse_runs
  ADD COLUMN overall_outcome VARCHAR(24) NULL,
  ADD COLUMN stage_results JSON NULL,
  ADD COLUMN warnings JSON NULL;

CREATE TABLE source_reading_editions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  parse_run_id BIGINT NULL,
  processing_run_id BIGINT NOT NULL,
  reading_version_no INT NOT NULL,
  supersedes_edition_id BIGINT NULL,
  schema_version INT NOT NULL DEFAULT 1,
  parser_name VARCHAR(80) NOT NULL,
  format VARCHAR(10) NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'READY',
  layout_mode VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
  overall_outcome VARCHAR(24) NOT NULL DEFAULT 'SUCCEEDED',
  capabilities JSON NOT NULL,
  reading_modes JSON NOT NULL,
  stage_results JSON NOT NULL,
  metadata JSON NOT NULL,
  outline JSON NOT NULL,
  warnings JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_reading_edition_document(document_id,id),
  UNIQUE KEY uk_reading_edition_version(document_id,reading_version_no),
  UNIQUE KEY uk_reading_edition_build(processing_run_id,schema_version),
  CONSTRAINT fk_reading_edition_run FOREIGN KEY(document_id,parse_run_id) REFERENCES document_parse_runs(document_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_processing_run FOREIGN KEY(document_id,processing_run_id) REFERENCES document_parse_runs(document_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_superseded FOREIGN KEY(document_id,supersedes_edition_id) REFERENCES source_reading_editions(document_id,id) ON DELETE RESTRICT,
  CONSTRAINT ck_reading_edition_format CHECK(format IN ('EPUB','PDF','DOC','DOCX','TXT')),
  CONSTRAINT ck_reading_edition_status CHECK(status IN ('BUILDING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_reading_units (
  id BIGINT NOT NULL AUTO_INCREMENT,
  edition_id BIGINT NOT NULL,
  unit_no INT NOT NULL,
  unit_type VARCHAR(20) NOT NULL,
  title VARCHAR(500) NOT NULL,
  source_path VARCHAR(1000) NULL,
  page_number INT NULL,
  nodes JSON NOT NULL,
  capabilities JSON NOT NULL,
  reading_modes JSON NOT NULL,
  warnings JSON NOT NULL,
  preview_ref VARCHAR(1000) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_reading_chapter_order(edition_id,unit_no),
  UNIQUE KEY uk_reading_chapter_edition(edition_id,id),
  CONSTRAINT fk_reading_chapter_edition FOREIGN KEY(edition_id) REFERENCES source_reading_editions(id) ON DELETE RESTRICT,
  CONSTRAINT ck_reading_chapter_order CHECK(unit_no > 0 AND (page_number IS NULL OR page_number > 0)),
  CONSTRAINT ck_reading_unit_type CHECK(unit_type IN ('CHAPTER','PAGE','TEXT_SECTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_reading_blocks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  edition_id BIGINT NOT NULL,
  unit_id BIGINT NOT NULL,
  block_no INT NOT NULL,
  local_id VARCHAR(80) NOT NULL,
  body LONGTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_reading_block_order(unit_id,block_no),
  UNIQUE KEY uk_reading_block_local(unit_id,local_id),
  UNIQUE KEY uk_reading_block_edition(edition_id,id),
  KEY ix_reading_block_search(edition_id,unit_id),
  CONSTRAINT fk_reading_block_chapter FOREIGN KEY(edition_id,unit_id) REFERENCES source_reading_units(edition_id,id) ON DELETE RESTRICT,
  CONSTRAINT ck_reading_block_order CHECK(block_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_reading_toc_nodes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  edition_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  order_no INT NOT NULL,
  title VARCHAR(500) NOT NULL,
  unit_id BIGINT NULL,
  local_id VARCHAR(80) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_reading_toc_owner(edition_id,id),
  CONSTRAINT fk_reading_toc_edition FOREIGN KEY(edition_id) REFERENCES source_reading_editions(id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_toc_parent FOREIGN KEY(edition_id,parent_id) REFERENCES source_reading_toc_nodes(edition_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_toc_unit FOREIGN KEY(edition_id,unit_id) REFERENCES source_reading_units(edition_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_rights_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  before_rights JSON NOT NULL,
  after_rights JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  CONSTRAINT fk_source_rights_document FOREIGN KEY(document_id) REFERENCES source_documents(id) ON DELETE RESTRICT,
  CONSTRAINT fk_source_rights_actor FOREIGN KEY(actor_id) REFERENCES sys_users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_reading_assets (
  id BIGINT NOT NULL AUTO_INCREMENT,
  edition_id BIGINT NOT NULL,
  asset_ref VARCHAR(1000) NOT NULL,
  asset_ref_hash CHAR(64) NOT NULL,
  asset_kind VARCHAR(24) NOT NULL DEFAULT 'IMAGE',
  original_format VARCHAR(32) NULL,
  unit_no INT NULL,
  page_number INT NULL,
  generator_version VARCHAR(100) NULL,
  parent_sha256 CHAR(64) NULL,
  storage_backend VARCHAR(16) NOT NULL,
  storage_bucket VARCHAR(128) NOT NULL,
  storage_key VARCHAR(512) NOT NULL,
  mime_type VARCHAR(100) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  byte_size BIGINT NOT NULL,
  pixel_width INT NOT NULL,
  pixel_height INT NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_reading_asset_ref(edition_id,asset_ref_hash),
  CONSTRAINT fk_reading_asset_edition FOREIGN KEY(edition_id) REFERENCES source_reading_editions(id) ON DELETE RESTRICT,
  CONSTRAINT ck_reading_asset_type CHECK(mime_type IN ('image/png','image/jpeg','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document') AND byte_size > 0 AND pixel_width >= 0 AND pixel_height >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE source_documents
  ADD CONSTRAINT fk_source_active_reader FOREIGN KEY(id,active_reading_edition_id) REFERENCES source_reading_editions(document_id,id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_source_reviewed_reader FOREIGN KEY(id,reviewed_reading_edition_id) REFERENCES source_reading_editions(document_id,id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_source_reviewed_parse FOREIGN KEY(id,reviewed_parse_run_id) REFERENCES document_parse_runs(document_id,id) ON DELETE RESTRICT;

CREATE TABLE source_confirmation_requests (
  user_id BIGINT NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  document_id BIGINT NOT NULL,
  parse_run_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(user_id,request_key),
  CONSTRAINT fk_source_confirmation_user FOREIGN KEY(user_id) REFERENCES sys_users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_source_confirmation_parse FOREIGN KEY(document_id,parse_run_id) REFERENCES document_parse_runs(document_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_reading_progress (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  edition_id BIGINT NOT NULL,
  unit_id BIGINT NOT NULL,
  block_id BIGINT NULL,
  text_offset INT NOT NULL DEFAULT 0,
  page_number INT NULL,
  font_size INT NOT NULL DEFAULT 18,
  reading_mode VARCHAR(32) NOT NULL DEFAULT 'STRUCTURED',
  lock_version INT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_reading_progress_owner(user_id,document_id,edition_id),
  CONSTRAINT fk_reading_progress_user FOREIGN KEY(user_id) REFERENCES sys_users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_progress_edition FOREIGN KEY(document_id,edition_id) REFERENCES source_reading_editions(document_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_progress_chapter FOREIGN KEY(edition_id,unit_id) REFERENCES source_reading_units(edition_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_progress_block FOREIGN KEY(edition_id,block_id) REFERENCES source_reading_blocks(edition_id,id) ON DELETE RESTRICT,
  CONSTRAINT ck_reading_progress_offset CHECK(text_offset >= 0 AND lock_version >= 0 AND font_size BETWEEN 14 AND 32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_reading_excerpts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  edition_id BIGINT NOT NULL,
  parse_run_id BIGINT NOT NULL,
  locator JSON NOT NULL,
  location_label VARCHAR(255) NOT NULL,
  quote_text TEXT NOT NULL,
  quote_hash CHAR(64) NOT NULL,
  selection_hash CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_reading_excerpt_selection(user_id,edition_id,selection_hash),
  CONSTRAINT fk_reading_excerpt_user FOREIGN KEY(user_id) REFERENCES sys_users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_excerpt_edition FOREIGN KEY(document_id,edition_id) REFERENCES source_reading_editions(document_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_reading_excerpt_run FOREIGN KEY(document_id,parse_run_id) REFERENCES document_parse_runs(document_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE revision_evidence
  ADD COLUMN parse_run_id BIGINT NULL,
  ADD COLUMN reading_edition_id BIGINT NULL,
  ADD COLUMN locator JSON NULL,
  ADD COLUMN quote_hash CHAR(64) NULL,
  ADD CONSTRAINT fk_evidence_reading_edition FOREIGN KEY(document_id,reading_edition_id) REFERENCES source_reading_editions(document_id,id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_evidence_parse_run FOREIGN KEY(document_id,parse_run_id) REFERENCES document_parse_runs(document_id,id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_evidence_reading_locator CHECK(reading_edition_id IS NULL OR (parse_run_id IS NOT NULL AND locator IS NOT NULL AND quote_hash IS NOT NULL));
UPDATE revision_evidence e JOIN document_segments s ON s.id=e.segment_id AND s.document_id=e.document_id SET e.parse_run_id=s.parse_run_id;
UPDATE revision_evidence SET quote_hash=SHA2(quote_text,256) WHERE quote_hash IS NULL;

CREATE TABLE knowledge_revision_proposals (
  id BIGINT NOT NULL AUTO_INCREMENT,
  resource_id BIGINT NOT NULL,
  base_revision_id BIGINT NOT NULL,
  submitted_by BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  payload JSON NOT NULL,
  lock_version INT NOT NULL DEFAULT 0,
  published_revision_id BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  KEY ix_knowledge_proposal_owner(submitted_by,status),
  CONSTRAINT fk_knowledge_proposal_base FOREIGN KEY(resource_id,base_revision_id) REFERENCES content_revisions(resource_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_knowledge_proposal_result FOREIGN KEY(resource_id,published_revision_id) REFERENCES content_revisions(resource_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_knowledge_proposal_user FOREIGN KEY(submitted_by) REFERENCES sys_users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_knowledge_proposal_status CHECK(status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_proposal_evidence (
  id BIGINT NOT NULL AUTO_INCREMENT,
  proposal_id BIGINT NOT NULL,
  evidence_no INT NOT NULL,
  document_id BIGINT NOT NULL,
  segment_id BIGINT NULL,
  parse_run_id BIGINT NULL,
  reading_edition_id BIGINT NULL,
  locator JSON NULL,
  location_label VARCHAR(255) NULL,
  quote_text TEXT NOT NULL,
  quote_hash CHAR(64) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_proposal_evidence_order(proposal_id,evidence_no),
  CONSTRAINT fk_proposal_evidence_proposal FOREIGN KEY(proposal_id) REFERENCES knowledge_revision_proposals(id) ON DELETE CASCADE,
  CONSTRAINT fk_proposal_evidence_document FOREIGN KEY(document_id) REFERENCES source_documents(id) ON DELETE RESTRICT,
  CONSTRAINT fk_proposal_evidence_segment FOREIGN KEY(document_id,segment_id) REFERENCES document_segments(document_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_proposal_evidence_edition FOREIGN KEY(document_id,reading_edition_id) REFERENCES source_reading_editions(document_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_proposal_evidence_run FOREIGN KEY(document_id,parse_run_id) REFERENCES document_parse_runs(document_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE review_tasks
  DROP CHECK ck_87db9a1fafb86044,
  ADD COLUMN proposal_id BIGINT NULL,
  ADD UNIQUE KEY uk_review_task_proposal(proposal_id),
  ADD CONSTRAINT fk_review_task_proposal FOREIGN KEY(proposal_id) REFERENCES knowledge_revision_proposals(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_review_target_one CHECK((revision_id IS NOT NULL)+(document_id IS NOT NULL)+(proposal_id IS NOT NULL)=1);
