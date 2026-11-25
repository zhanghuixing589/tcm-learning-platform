-- Preserve historical category identities and revision references. Only the new
-- typed directories are available for new selection; never guess medical classes.
ALTER TABLE content_categories
 ADD COLUMN knowledge_type VARCHAR(24) NULL,
 ADD COLUMN legacy_archive TINYINT NOT NULL DEFAULT 0,
 ADD COLUMN lock_version INT NOT NULL DEFAULT 0;
UPDATE content_categories SET legacy_archive=1,active=0 WHERE scope='KNOWLEDGE';
ALTER TABLE content_categories
 ADD CONSTRAINT ck_category_knowledge_type CHECK (
  (scope='KNOWLEDGE' AND (
   (legacy_archive=1 AND knowledge_type IS NULL AND active=0) OR
   (legacy_archive=0 AND knowledge_type IS NOT NULL AND knowledge_type IN ('CONCEPT','HERB','FORMULA','EFFECT','SYMPTOM','MERIDIAN')))) OR
  (scope<>'KNOWLEDGE' AND knowledge_type IS NULL AND legacy_archive=0)),
 ADD UNIQUE KEY uk_directory_type_name(knowledge_type,name),
 ADD KEY ix_directory_type_active(scope,knowledge_type,active,sort_order,id);
ALTER TABLE content_tags ADD COLUMN lock_version INT NOT NULL DEFAULT 0;

INSERT INTO content_categories(code,name,scope,knowledge_type,sort_order) VALUES
 ('kdir-concept-01','中医基础理论','KNOWLEDGE','CONCEPT',10),
 ('kdir-concept-02','阴阳五行','KNOWLEDGE','CONCEPT',20),
 ('kdir-concept-03','藏象学说','KNOWLEDGE','CONCEPT',30),
 ('kdir-concept-04','气血津液','KNOWLEDGE','CONCEPT',40),
 ('kdir-concept-05','病因病机','KNOWLEDGE','CONCEPT',50),
 ('kdir-concept-06','四诊与辨证','KNOWLEDGE','CONCEPT',60),
 ('kdir-herb-01','解表药','KNOWLEDGE','HERB',10),
 ('kdir-herb-02','清热药','KNOWLEDGE','HERB',20),
 ('kdir-herb-03','补虚药','KNOWLEDGE','HERB',30),
 ('kdir-herb-04','活血化瘀药','KNOWLEDGE','HERB',40),
 ('kdir-herb-05','化痰止咳平喘药','KNOWLEDGE','HERB',50),
 ('kdir-herb-06','理气药','KNOWLEDGE','HERB',60),
 ('kdir-formula-01','解表剂','KNOWLEDGE','FORMULA',10),
 ('kdir-formula-02','清热剂','KNOWLEDGE','FORMULA',20),
 ('kdir-formula-03','补益剂','KNOWLEDGE','FORMULA',30),
 ('kdir-formula-04','和解剂','KNOWLEDGE','FORMULA',40),
 ('kdir-formula-05','理气剂','KNOWLEDGE','FORMULA',50),
 ('kdir-formula-06','理血剂','KNOWLEDGE','FORMULA',60),
 ('kdir-effect-01','清热类','KNOWLEDGE','EFFECT',10),
 ('kdir-effect-02','补益类','KNOWLEDGE','EFFECT',20),
 ('kdir-effect-03','活血类','KNOWLEDGE','EFFECT',30),
 ('kdir-effect-04','祛湿类','KNOWLEDGE','EFFECT',40),
 ('kdir-effect-05','理气类','KNOWLEDGE','EFFECT',50),
 ('kdir-symptom-01','全身症状','KNOWLEDGE','SYMPTOM',10),
 ('kdir-symptom-02','呼吸相关症状','KNOWLEDGE','SYMPTOM',20),
 ('kdir-symptom-03','消化相关症状','KNOWLEDGE','SYMPTOM',30),
 ('kdir-symptom-04','睡眠相关症状','KNOWLEDGE','SYMPTOM',40),
 ('kdir-symptom-05','疼痛相关症状','KNOWLEDGE','SYMPTOM',50),
 ('kdir-meridian-01','十二正经','KNOWLEDGE','MERIDIAN',10),
 ('kdir-meridian-02','奇经八脉','KNOWLEDGE','MERIDIAN',20),
 ('kdir-meridian-03','其他经络知识','KNOWLEDGE','MERIDIAN',30);
INSERT INTO sys_permissions(code,name) VALUES('knowledge:taxonomy:manage','知识目录及标签维护');
INSERT INTO sys_role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM sys_roles r JOIN sys_permissions p ON p.code='knowledge:taxonomy:manage' WHERE r.code='ADMIN';

-- Keep the binding immutable even outside HTTP. Existing frozen revisions with
-- archived categories remain untouched; new assignments must be typed and active.
DELIMITER $$
CREATE TRIGGER trg_directory_binding_immutable BEFORE UPDATE ON content_categories FOR EACH ROW
BEGIN
 IF NOT (NEW.knowledge_type <=> OLD.knowledge_type) OR NEW.legacy_archive<>OLD.legacy_archive OR NEW.scope<>OLD.scope THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Category type and archive identity are immutable';
 END IF;
END$$
CREATE TRIGGER trg_revision_directory_insert BEFORE INSERT ON content_revisions FOR EACH ROW
BEGIN
 DECLARE kt VARCHAR(24);
 SET kt=(SELECT knowledge_type FROM knowledge_entries WHERE resource_id=NEW.resource_id);
 IF kt IS NOT NULL AND NEW.category_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM content_categories WHERE id=NEW.category_id AND scope='KNOWLEDGE' AND knowledge_type=kt AND active=1 AND legacy_archive=0) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Knowledge directory does not match its type or is unavailable';
 END IF;
END$$
CREATE TRIGGER trg_revision_directory_update BEFORE UPDATE ON content_revisions FOR EACH ROW
BEGIN
 DECLARE kt VARCHAR(24);
 IF NOT (NEW.category_id <=> OLD.category_id) OR NEW.resource_id<>OLD.resource_id THEN
  SET kt=(SELECT knowledge_type FROM knowledge_entries WHERE resource_id=NEW.resource_id);
  IF kt IS NOT NULL AND NEW.category_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM content_categories WHERE id=NEW.category_id AND scope='KNOWLEDGE' AND knowledge_type=kt AND active=1 AND legacy_archive=0) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Knowledge directory does not match its type or is unavailable';
  END IF;
 END IF;
END$$
DELIMITER ;
