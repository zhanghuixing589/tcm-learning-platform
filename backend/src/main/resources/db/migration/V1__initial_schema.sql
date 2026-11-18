-- MySQL8.0.16+；仅在指定空库执行；无DROP/USE/CREATE DATABASE。
-- DDL不整体回滚。已提交内容的不可变性由业务事务实现。
SET NAMES utf8mb4;
SET time_zone = '+00:00';
CREATE TABLE `sys_users` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `username` VARCHAR(64) NOT NULL COMMENT '登录名',
  `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt散列',
  `nickname` VARCHAR(80) NOT NULL COMMENT '昵称',
  `avatar_path` VARCHAR(512) NULL COMMENT '头像路径',
  `bio` VARCHAR(500) NULL COMMENT '简介',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `credential_version` INT NOT NULL DEFAULT 0 COMMENT '密码重置或禁用时递增',
  `last_login_at` DATETIME(3) NULL COMMENT '最近登录',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_72b34810b7651819` (`username`),
  CONSTRAINT `ck_72b34810b7651819` CHECK (status IN ('ACTIVE','DISABLED')),
  CONSTRAINT `ck_c43bf7789404c334` CHECK (credential_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户账户；禁用保留历史';

CREATE TABLE `sys_roles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `code` VARCHAR(32) NOT NULL COMMENT 'LEARNER/CONTRIBUTOR/REVIEWER/ADMIN',
  `name` VARCHAR(64) NOT NULL COMMENT '名称',
  `description` VARCHAR(255) NULL COMMENT '职责',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_c532ff74d6db30b6` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色，不含游客';

CREATE TABLE `sys_permissions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `code` VARCHAR(80) NOT NULL COMMENT '权限编码',
  `name` VARCHAR(100) NOT NULL COMMENT '名称',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_f9ef476bd89d3bb4` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口权限';

CREATE TABLE `sys_user_roles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `role_id` BIGINT NOT NULL COMMENT '角色',
  `granted_by` BIGINT NULL COMMENT '授权人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_75fce77299278151` (`user_id`, `role_id`),
  KEY `ix_75fce77299278151` (`role_id`),
  KEY `ix_d3ef7732f6325f11` (`granted_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色';

CREATE TABLE `sys_role_permissions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `role_id` BIGINT NOT NULL COMMENT '角色',
  `permission_id` BIGINT NOT NULL COMMENT '权限',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_c583ab1bb8c133b3` (`role_id`, `permission_id`),
  KEY `ix_c583ab1bb8c133b3` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限';

CREATE TABLE `sys_role_applications` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '申请人',
  `role_id` BIGINT NOT NULL COMMENT '接口仅允许CONTRIBUTOR',
  `statement` TEXT NOT NULL COMMENT '说明',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态',
  `reviewer_id` BIGINT NULL COMMENT '处理人',
  `review_note` TEXT NULL COMMENT '意见',
  `reviewed_at` DATETIME(3) NULL COMMENT '处理时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_f0bf0c9d93f9d577` (`status`, `created_at`),
  KEY `ix_19b2f34345aa66ea` (`user_id`, `created_at`),
  KEY `ix_f35f5cf95af47d96` (`role_id`),
  KEY `ix_df3557537fa12c0a` (`reviewer_id`),
  CONSTRAINT `ck_f0bf0c9d93f9d577` CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT `ck_19b2f34345aa66ea` CHECK (reviewer_id IS NULL OR reviewer_id <> user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='贡献资格申请';

CREATE TABLE `auth_refresh_tokens` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `token_hash` CHAR(64) NOT NULL COMMENT '令牌SHA256，不存原文',
  `family_key` CHAR(36) NOT NULL COMMENT '登录轮换链',
  `expires_at` DATETIME(3) NOT NULL COMMENT '到期',
  `revoked_at` DATETIME(3) NULL COMMENT '撤销',
  `replaced_by_id` BIGINT NULL COMMENT '后继令牌',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_1bb483236d253539` (`token_hash`),
  KEY `ix_1bb483236d253539` (`user_id`, `expires_at`),
  KEY `ix_d9438839816f2437` (`family_key`),
  KEY `ix_41f751692baf1b3e` (`replaced_by_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='刷新令牌轮换撤销';

CREATE TABLE `file_objects` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `sha256` CHAR(64) NOT NULL COMMENT '内容哈希',
  `storage_key` VARCHAR(512) NOT NULL COMMENT '根目录内相对键',
  `original_name` VARCHAR(255) NOT NULL COMMENT '原名',
  `mime_type` VARCHAR(100) NOT NULL COMMENT '类型',
  `byte_size` BIGINT NOT NULL COMMENT '字节数',
  `uploaded_by` BIGINT NOT NULL COMMENT '上传人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bf7f3df30e51bef4` (`sha256`),
  KEY `ix_bf7f3df30e51bef4` (`uploaded_by`),
  CONSTRAINT `ck_bf7f3df30e51bef4` CHECK (byte_size > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件与完全去重';

CREATE TABLE `content_categories` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `parent_id` BIGINT NULL COMMENT '父分类',
  `code` VARCHAR(64) NOT NULL COMMENT '编码',
  `name` VARCHAR(80) NOT NULL COMMENT '名称',
  `scope` VARCHAR(24) NOT NULL COMMENT 'KNOWLEDGE/LEARNING/COMMUNITY',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `active` TINYINT NOT NULL DEFAULT 1 COMMENT '启用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_212f04ee647617df` (`code`),
  KEY `ix_212f04ee647617df` (`scope`, `parent_id`, `sort_order`),
  KEY `ix_28c25a250b249a5d` (`parent_id`),
  CONSTRAINT `ck_212f04ee647617df` CHECK (active IN (0,1)),
  CONSTRAINT `ck_28c25a250b249a5d` CHECK (scope IN ('KNOWLEDGE','LEARNING','COMMUNITY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分类';

CREATE TABLE `content_tags` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `name` VARCHAR(64) NOT NULL COMMENT '名称',
  `active` TINYINT NOT NULL DEFAULT 1 COMMENT '启用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_3060a416d9d94bb1` (`name`),
  CONSTRAINT `ck_3060a416d9d94bb1` CHECK (active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标签';

CREATE TABLE `content_resources` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `resource_type` VARCHAR(24) NOT NULL COMMENT '资源类型',
  `owner_id` BIGINT NOT NULL COMMENT '负责用户',
  `visibility_status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '可见状态',
  `published_revision_id` BIGINT NULL COMMENT '当前正式版本',
  `lock_version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  `published_at` DATETIME(3) NULL COMMENT '首次公开时间',
  `suspended_reason` VARCHAR(1000) NULL COMMENT '停用说明',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_f56c793b8ae21676` (`resource_type`, `visibility_status`, `published_at`),
  KEY `ix_12403e5a39d643b6` (`owner_id`, `created_at`),
  KEY `ix_15cc166b2fef4ec8` (`id`, `published_revision_id`),
  CONSTRAINT `ck_f56c793b8ae21676` CHECK (resource_type IN ('KNOWLEDGE','TOPIC','CARD','QUESTION','POST','ARTICLE')),
  CONSTRAINT `ck_12403e5a39d643b6` CHECK (visibility_status IN ('DRAFT','PUBLISHED','SUSPENDED','ARCHIVED')),
  CONSTRAINT `ck_15cc166b2fef4ec8` CHECK (visibility_status <> 'PUBLISHED' OR published_revision_id IS NOT NULL),
  CONSTRAINT `ck_e7e84cb5b537e79e` CHECK (lock_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容身份与可见性';

CREATE TABLE `content_revisions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `resource_id` BIGINT NOT NULL COMMENT '内容身份',
  `version_no` INT NOT NULL COMMENT '版本号',
  `base_revision_id` BIGINT NULL COMMENT '修订依据版本',
  `category_id` BIGINT NULL COMMENT '分类',
  `title` VARCHAR(255) NOT NULL COMMENT '标题',
  `body` LONGTEXT NOT NULL COMMENT '受控Markdown正文',
  `revision_state` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '审核状态',
  `change_note` VARCHAR(1000) NULL COMMENT '修改说明',
  `created_by` BIGINT NOT NULL COMMENT '编辑人',
  `submitted_at` DATETIME(3) NULL COMMENT '提交时间',
  `approved_at` DATETIME(3) NULL COMMENT '通过时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_e73fb180e185df41` (`resource_id`, `version_no`),
  UNIQUE KEY `uk_5a664660b4695948` (`resource_id`, `id`),
  KEY `ix_e73fb180e185df41` (`revision_state`, `created_at`),
  KEY `ix_5a664660b4695948` (`category_id`),
  KEY `ix_0b512da643e65c3e` (`created_by`),
  KEY `ix_5418aaa0a17c611c` (`resource_id`, `base_revision_id`),
  CONSTRAINT `ck_e73fb180e185df41` CHECK (version_no > 0),
  CONSTRAINT `ck_5a664660b4695948` CHECK (revision_state IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提交后冻结的内容版本';

CREATE TABLE `revision_tags` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `revision_id` BIGINT NOT NULL COMMENT '版本',
  `tag_id` BIGINT NOT NULL COMMENT '标签',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ab72a0a4bbc30806` (`revision_id`, `tag_id`),
  KEY `ix_ab72a0a4bbc30806` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本标签';

CREATE TABLE `resource_bookmarks` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `resource_id` BIGINT NOT NULL COMMENT '内容',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_1497ee4f02cfbd2f` (`user_id`, `resource_id`),
  KEY `ix_1497ee4f02cfbd2f` (`user_id`, `created_at`),
  KEY `ix_6344294cdb722620` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一收藏';

CREATE TABLE `resource_likes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `resource_id` BIGINT NOT NULL COMMENT '内容',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_48c59751f226b2dd` (`user_id`, `resource_id`),
  KEY `ix_48c59751f226b2dd` (`user_id`, `created_at`),
  KEY `ix_5417591c82cedeeb` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点赞关联';

CREATE TABLE `user_resource_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `resource_id` BIGINT NOT NULL COMMENT '资源',
  `last_viewed_at` DATETIME(3) NOT NULL COMMENT '最近访问',
  `view_count` INT NOT NULL DEFAULT 1 COMMENT '有效次数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_459e8527212fd585` (`user_id`, `resource_id`),
  KEY `ix_459e8527212fd585` (`user_id`, `last_viewed_at`),
  KEY `ix_b8b72daf594bc3e0` (`resource_id`),
  CONSTRAINT `ck_459e8527212fd585` CHECK (view_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='最近浏览聚合，可重建';

CREATE TABLE `resource_feedback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '反馈人',
  `resource_id` BIGINT NULL COMMENT '资源',
  `revision_id` BIGINT NULL COMMENT '当时版本',
  `kind` VARCHAR(24) NOT NULL COMMENT '反馈类型',
  `content` TEXT NOT NULL COMMENT '内容',
  `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '处理状态',
  `handler_id` BIGINT NULL COMMENT '处理人',
  `resolution` TEXT NULL COMMENT '结果',
  `resolved_at` DATETIME(3) NULL COMMENT '处理时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_480bba966de47368` (`status`, `created_at`),
  KEY `ix_a359abeda001d7b4` (`user_id`, `created_at`),
  KEY `ix_2338654a89d76ed1` (`resource_id`),
  KEY `ix_816b32c602efea1b` (`handler_id`),
  KEY `ix_d7b53e1c347b6de5` (`resource_id`, `revision_id`),
  CONSTRAINT `ck_480bba966de47368` CHECK (kind IN ('CORRECTION','KNOWLEDGE_REQUEST')),
  CONSTRAINT `ck_a359abeda001d7b4` CHECK (status IN ('OPEN','PROCESSING','RESOLVED','REJECTED')),
  CONSTRAINT `ck_2338654a89d76ed1` CHECK (revision_id IS NULL OR resource_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='纠错与知识需求';

CREATE TABLE `user_notifications` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '接收者',
  `event_key` VARCHAR(120) NOT NULL COMMENT '幂等事件键',
  `kind` VARCHAR(40) NOT NULL COMMENT '通知类型',
  `title` VARCHAR(160) NOT NULL COMMENT '标题',
  `body` TEXT NOT NULL COMMENT '消息',
  `resource_id` BIGINT NULL COMMENT '资源',
  `relative_path` VARCHAR(512) NULL COMMENT '站内路径',
  `read_at` DATETIME(3) NULL COMMENT '已读时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_7cd3920473ccfe58` (`user_id`, `event_key`),
  KEY `ix_7cd3920473ccfe58` (`user_id`, `read_at`, `created_at`),
  KEY `ix_53246787fb0e0c96` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知';

CREATE TABLE `sys_audit_logs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `actor_id` BIGINT NULL COMMENT '操作者',
  `trace_id` VARCHAR(64) NOT NULL COMMENT '追踪号',
  `action` VARCHAR(80) NOT NULL COMMENT '动作',
  `object_type` VARCHAR(64) NOT NULL COMMENT '对象类型',
  `object_key` VARCHAR(100) NOT NULL COMMENT '历史对象键',
  `summary` JSON NULL COMMENT '脱敏摘要',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_b51bab1e3c6acb6c` (`object_type`, `object_key`, `created_at`),
  KEY `ix_0923c5dd7712ec72` (`actor_id`, `created_at`),
  KEY `ix_354f93c5ee5ef75e` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='追加审计，不存密码密钥';

CREATE TABLE `source_documents` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `file_id` BIGINT NULL COMMENT '文件',
  `origin_revision_id` BIGINT NULL COMMENT '社区文章版本',
  `parent_document_id` BIGINT NULL COMMENT '上一资料版本',
  `title` VARCHAR(255) NOT NULL COMMENT '标题',
  `source_type` VARCHAR(24) NOT NULL COMMENT '来源类型',
  `author` VARCHAR(255) NULL COMMENT '作者',
  `publisher` VARCHAR(255) NULL COMMENT '出版机构',
  `publication_year` SMALLINT NULL COMMENT '出版年',
  `source_url` VARCHAR(1500) NULL COMMENT '来源链接',
  `bibliographic_note` TEXT NULL COMMENT '书目信息',
  `submitted_by` BIGINT NOT NULL COMMENT '提交人',
  `review_state` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '审核状态',
  `availability` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '可用状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_f583f910eca6a4a8` (`review_state`, `created_at`),
  KEY `ix_f72d5577b763a033` (`submitted_by`, `created_at`),
  KEY `ix_6efbdc451046d998` (`file_id`),
  KEY `ix_46d59d6ffb82f97a` (`origin_revision_id`),
  KEY `ix_1993d7acdab08827` (`parent_document_id`),
  CONSTRAINT `ck_f583f910eca6a4a8` CHECK (source_type IN ('BOOK','PAPER','WEB','COMMUNITY','OTHER')),
  CONSTRAINT `ck_f72d5577b763a033` CHECK (review_state IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT `ck_6efbdc451046d998` CHECK (availability IN ('ACTIVE','SUSPENDED')),
  CONSTRAINT `ck_46d59d6ffb82f97a` CHECK (file_id IS NOT NULL OR origin_revision_id IS NOT NULL OR source_url IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='来源资料，提交后冻结元信息，新修订另建记录';

CREATE TABLE `document_parse_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `document_id` BIGINT NOT NULL COMMENT '资料',
  `parse_version` INT NOT NULL COMMENT '解析序号',
  `parser_name` VARCHAR(80) NOT NULL COMMENT '解析器版本',
  `status` VARCHAR(20) NOT NULL DEFAULT 'QUEUED' COMMENT '解析状态',
  `error_code` VARCHAR(80) NULL COMMENT '失败类型',
  `error_message` VARCHAR(1000) NULL COMMENT '脱敏摘要',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_9bafef928670c997` (`document_id`, `parse_version`),
  UNIQUE KEY `uk_7bcb1164de1636ec` (`document_id`, `id`),
  CONSTRAINT `ck_9bafef928670c997` CHECK (parse_version > 0),
  CONSTRAINT `ck_7bcb1164de1636ec` CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化解析批次';

CREATE TABLE `document_segments` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `document_id` BIGINT NOT NULL COMMENT '资料',
  `parse_run_id` BIGINT NOT NULL COMMENT '解析批次',
  `segment_no` INT NOT NULL COMMENT '段落号',
  `heading_path` VARCHAR(500) NULL COMMENT '章节',
  `page_label` VARCHAR(80) NULL COMMENT '页码位置',
  `body` LONGTEXT NOT NULL COMMENT '原文',
  `content_hash` CHAR(64) NOT NULL COMMENT '文本哈希',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_c237fdfa9a755f3f` (`parse_run_id`, `segment_no`),
  UNIQUE KEY `uk_aefb7ab0f265e813` (`document_id`, `id`),
  KEY `ix_c237fdfa9a755f3f` (`document_id`, `parse_run_id`),
  CONSTRAINT `ck_c237fdfa9a755f3f` CHECK (segment_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变原文段落';

CREATE TABLE `revision_evidence` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `revision_id` BIGINT NOT NULL COMMENT '被支撑版本',
  `document_id` BIGINT NOT NULL COMMENT '资料',
  `segment_id` BIGINT NULL COMMENT '原文段落',
  `location_label` VARCHAR(255) NULL COMMENT '人工定位',
  `quote_text` TEXT NOT NULL COMMENT '支持片段',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_effd8eb658a92d5f` (`revision_id`, `id`),
  KEY `ix_effd8eb658a92d5f` (`document_id`),
  KEY `ix_17d182b71f1c6cb7` (`document_id`, `segment_id`),
  CONSTRAINT `ck_effd8eb658a92d5f` CHECK (segment_id IS NOT NULL OR location_label IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容版本来源依据';

CREATE TABLE `review_tasks` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `revision_id` BIGINT NULL COMMENT '版本',
  `document_id` BIGINT NULL COMMENT '资料',
  `submitted_by` BIGINT NOT NULL COMMENT '提交人',
  `reviewer_id` BIGINT NULL COMMENT '审核人',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
  `review_note` TEXT NULL COMMENT '意见',
  `reviewed_at` DATETIME(3) NULL COMMENT '处理时间',
  `lock_version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_87db9a1fafb86044` (`revision_id`),
  UNIQUE KEY `uk_d91905abcbf1a6b8` (`document_id`),
  KEY `ix_87db9a1fafb86044` (`status`, `created_at`),
  KEY `ix_d91905abcbf1a6b8` (`reviewer_id`, `status`),
  KEY `ix_adf85c8d0e18f0dd` (`submitted_by`),
  CONSTRAINT `ck_87db9a1fafb86044` CHECK ((revision_id IS NOT NULL) + (document_id IS NOT NULL) = 1),
  CONSTRAINT `ck_d91905abcbf1a6b8` CHECK (reviewer_id IS NULL OR reviewer_id <> submitted_by),
  CONSTRAINT `ck_adf85c8d0e18f0dd` CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT `ck_2227c997bb80535b` CHECK (status NOT IN ('APPROVED','REJECTED') OR (reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL AND review_note IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一审核，一份冻结提交一个任务';

CREATE TABLE `review_events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `review_task_id` BIGINT NOT NULL COMMENT '任务',
  `actor_id` BIGINT NOT NULL COMMENT '操作者',
  `action` VARCHAR(24) NOT NULL COMMENT '审核动作',
  `note` TEXT NULL COMMENT '意见',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_8965680a0c8cbe29` (`review_task_id`, `created_at`),
  KEY `ix_7116a56f0c4f10dc` (`actor_id`),
  CONSTRAINT `ck_8965680a0c8cbe29` CHECK (action IN ('CLAIM','APPROVE','REJECT','WITHDRAW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核操作历史';

CREATE TABLE `async_jobs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `job_type` VARCHAR(40) NOT NULL COMMENT 'PARSE/EXTRACT/VECTOR_INDEX/GRAPH_SYNC/REMOVE_INDEX/PROFILE_REFRESH/BACKUP',
  `idempotency_key` VARCHAR(191) NOT NULL COMMENT '动作对象版本配置的幂等键',
  `revision_id` BIGINT NULL COMMENT '版本目标',
  `document_id` BIGINT NULL COMMENT '资料目标',
  `payload` JSON NULL COMMENT '索引代次等执行参数',
  `status` VARCHAR(20) NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '尝试次数',
  `max_attempts` INT NOT NULL DEFAULT 3 COMMENT '最大次数',
  `available_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最早执行',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止',
  `run_token` CHAR(36) NULL COMMENT '本次领取令牌',
  `last_error` VARCHAR(1000) NULL COMMENT '脱敏错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_45431ee3e499f688` (`idempotency_key`),
  KEY `ix_45431ee3e499f688` (`status`, `available_at`, `id`),
  KEY `ix_f8a4bd08e6912c73` (`lease_until`),
  KEY `ix_c3f692218d11a771` (`revision_id`),
  KEY `ix_f6dd771fb9aa7c21` (`document_id`),
  CONSTRAINT `ck_45431ee3e499f688` CHECK (status IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','DEAD','CANCELLED')),
  CONSTRAINT `ck_f8a4bd08e6912c73` CHECK (attempt_count >= 0 AND max_attempts > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事务内outbox与异步任务';

CREATE TABLE `async_job_attempts` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `job_id` BIGINT NOT NULL COMMENT '任务',
  `attempt_no` INT NOT NULL COMMENT '尝试序号',
  `run_token` CHAR(36) NOT NULL COMMENT '领取令牌',
  `started_at` DATETIME(3) NOT NULL COMMENT '开始',
  `finished_at` DATETIME(3) NULL COMMENT '结束',
  `status` VARCHAR(20) NOT NULL COMMENT '执行状态',
  `error_message` VARCHAR(1000) NULL COMMENT '脱敏错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_98e6958a21ea2d00` (`job_id`, `attempt_no`),
  CONSTRAINT `ck_98e6958a21ea2d00` CHECK (attempt_no > 0),
  CONSTRAINT `ck_004fd7e90794f6d3` CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务执行历史';

CREATE TABLE `knowledge_entries` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `resource_id` BIGINT NOT NULL COMMENT '知识资源',
  `knowledge_type` VARCHAR(24) NOT NULL COMMENT '知识类别',
  `canonical_name` VARCHAR(160) NOT NULL COMMENT '规范名称',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_5bc93405a26489da` (`resource_id`),
  UNIQUE KEY `uk_1a9b7a88b4b1b5e5` (`knowledge_type`, `canonical_name`),
  CONSTRAINT `ck_5bc93405a26489da` CHECK (knowledge_type IN ('CONCEPT','HERB','FORMULA','EFFECT','SYMPTOM','MERIDIAN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识身份与规范名称';

CREATE TABLE `knowledge_aliases` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `knowledge_resource_id` BIGINT NOT NULL COMMENT '知识',
  `alias` VARCHAR(160) NOT NULL COMMENT '别名',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_1029a1e2b9d96d8d` (`knowledge_resource_id`, `alias`),
  KEY `ix_1029a1e2b9d96d8d` (`alias`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='名称别名索引';

CREATE TABLE `herb_profiles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `revision_id` BIGINT NOT NULL COMMENT '版本',
  `nature` VARCHAR(160) NULL COMMENT '性',
  `flavor` VARCHAR(160) NULL COMMENT '味',
  `origin_note` TEXT NULL COMMENT '来源说明',
  `precautions` TEXT NULL COMMENT '注意事项',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_e8b28adf0f642d12` (`revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药材版本属性';

CREATE TABLE `formula_profiles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `revision_id` BIGINT NOT NULL COMMENT '版本',
  `origin_text` VARCHAR(500) NOT NULL COMMENT '出处',
  `function_summary` TEXT NULL COMMENT '功效说明',
  `indication_summary` TEXT NULL COMMENT '主治知识说明',
  `precautions` TEXT NULL COMMENT '注意事项',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_c48f4652c3b1f73c` (`revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='方剂版本属性';

CREATE TABLE `formula_ingredients` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `formula_resource_id` BIGINT NOT NULL COMMENT '方剂',
  `formula_revision_id` BIGINT NOT NULL COMMENT '方剂版本',
  `herb_resource_id` BIGINT NOT NULL COMMENT '药材',
  `herb_revision_id` BIGINT NOT NULL COMMENT '药材依据版本',
  `role` VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED' COMMENT '组成角色',
  `dose_value` DECIMAL(12,4) NULL COMMENT '资料数值，仅学习辅助',
  `dose_unit` VARCHAR(32) NULL COMMENT '保留原单位',
  `dose_text` VARCHAR(160) NULL COMMENT '原文剂量',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '顺序',
  `evidence_id` BIGINT NOT NULL COMMENT '方剂版本依据',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_572b864559d0f982` (`formula_revision_id`, `herb_resource_id`),
  KEY `ix_572b864559d0f982` (`formula_resource_id`),
  KEY `ix_34168b2baf72c0cb` (`herb_resource_id`),
  KEY `ix_2e1cf62092a893a9` (`formula_resource_id`, `formula_revision_id`),
  KEY `ix_12cae55418fb89f4` (`herb_resource_id`, `herb_revision_id`),
  KEY `ix_d5b7fe4f11746e6f` (`formula_revision_id`, `evidence_id`),
  CONSTRAINT `ck_572b864559d0f982` CHECK (role IN ('JUN','CHEN','ZUO','SHI','UNSPECIFIED')),
  CONSTRAINT `ck_34168b2baf72c0cb` CHECK (dose_value IS NULL OR dose_value > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有依据的方剂版本组成';

CREATE TABLE `knowledge_relations` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `source_resource_id` BIGINT NOT NULL COMMENT '起点',
  `source_revision_id` BIGINT NOT NULL COMMENT '起点版本',
  `target_resource_id` BIGINT NOT NULL COMMENT '终点',
  `target_revision_id` BIGINT NOT NULL COMMENT '终点版本',
  `relation_type` VARCHAR(32) NOT NULL COMMENT '关系类型',
  `explanation` TEXT NULL COMMENT '说明',
  `evidence_id` BIGINT NOT NULL COMMENT '起点版本依据',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_1430f32ff894e91d` (`source_revision_id`, `target_resource_id`, `relation_type`),
  KEY `ix_1430f32ff894e91d` (`source_resource_id`),
  KEY `ix_a99302f5cac61199` (`target_resource_id`),
  KEY `ix_1dd15fcc7452591a` (`source_resource_id`, `source_revision_id`),
  KEY `ix_f3f6a50823c702a9` (`target_resource_id`, `target_revision_id`),
  KEY `ix_040e3b9a7b09b8f6` (`source_revision_id`, `evidence_id`),
  CONSTRAINT `ck_1430f32ff894e91d` CHECK (relation_type IN ('HAS_EFFECT','INDICATED_FOR','ENTERS_MERIDIAN','COMPATIBLE_WITH','RELATED_TO')),
  CONSTRAINT `ck_a99302f5cac61199` CHECK (source_resource_id <> target_resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有依据的知识关系事实';

CREATE TABLE `learning_topics` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `resource_id` BIGINT NOT NULL COMMENT 'TOPIC资源',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_e139223ffd7869d7` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='专题身份';

CREATE TABLE `topic_profiles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `revision_id` BIGINT NOT NULL COMMENT '版本',
  `objectives` TEXT NOT NULL COMMENT '学习目标',
  `prerequisites` TEXT NULL COMMENT '所需基础',
  `level` VARCHAR(20) NOT NULL DEFAULT 'BEGINNER' COMMENT '基础级别',
  `estimated_minutes` INT NULL COMMENT '建议时长',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dc536c36cac57e12` (`revision_id`),
  CONSTRAINT `ck_dc536c36cac57e12` CHECK (level IN ('BEGINNER','INTERMEDIATE')),
  CONSTRAINT `ck_4268fce83168aab3` CHECK (estimated_minutes IS NULL OR estimated_minutes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='专题版本属性';

CREATE TABLE `learning_cards` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `resource_id` BIGINT NOT NULL COMMENT 'CARD资源',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_7423c333a81f8b22` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卡片身份';

CREATE TABLE `topic_cards` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `topic_revision_id` BIGINT NOT NULL COMMENT '专题版本',
  `card_resource_id` BIGINT NOT NULL COMMENT '卡片',
  `card_revision_id` BIGINT NOT NULL COMMENT '卡片版本',
  `position` INT NOT NULL COMMENT '目录位置',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_105e71487ea3729b` (`topic_revision_id`, `position`),
  UNIQUE KEY `uk_1f3bf37081e35901` (`topic_revision_id`, `card_resource_id`),
  KEY `ix_105e71487ea3729b` (`card_resource_id`),
  KEY `ix_1f3bf37081e35901` (`card_resource_id`, `card_revision_id`),
  CONSTRAINT `ck_105e71487ea3729b` CHECK (position > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='专题版本目录，固定卡片版本';

CREATE TABLE `card_knowledge_links` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `card_revision_id` BIGINT NOT NULL COMMENT '卡片版本',
  `knowledge_resource_id` BIGINT NOT NULL COMMENT '知识',
  `knowledge_revision_id` BIGINT NOT NULL COMMENT '依据版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ec5191eaaa78fdcd` (`card_revision_id`, `knowledge_resource_id`),
  KEY `ix_ec5191eaaa78fdcd` (`knowledge_resource_id`),
  KEY `ix_693c2e88a4b8e949` (`knowledge_resource_id`, `knowledge_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卡片版本关联知识';

CREATE TABLE `question_profiles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `revision_id` BIGINT NOT NULL COMMENT '题目版本，题干在正文',
  `question_type` VARCHAR(20) NOT NULL COMMENT '类型',
  `difficulty` VARCHAR(16) NOT NULL DEFAULT 'EASY' COMMENT '难度',
  `explanation` TEXT NOT NULL COMMENT '提交后展示的解析',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_725109c5348f80eb` (`revision_id`),
  CONSTRAINT `ck_725109c5348f80eb` CHECK (question_type IN ('SINGLE','MULTIPLE','TRUE_FALSE')),
  CONSTRAINT `ck_222cba4db9eb9a53` CHECK (difficulty IN ('EASY','MEDIUM','HARD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目版本私有属性，禁止直接返回作答API';

CREATE TABLE `question_options` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `question_revision_id` BIGINT NOT NULL COMMENT '题目版本',
  `option_key` VARCHAR(8) NOT NULL COMMENT '稳定选项键',
  `option_text` TEXT NOT NULL COMMENT '内容',
  `is_correct` TINYINT NOT NULL COMMENT '标准答案',
  `position` INT NOT NULL COMMENT '顺序',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fe7d60371f0df1d0` (`question_revision_id`, `option_key`),
  UNIQUE KEY `uk_04bf729d2a7e4f86` (`question_revision_id`, `position`),
  CONSTRAINT `ck_fe7d60371f0df1d0` CHECK (is_correct IN (0,1)),
  CONSTRAINT `ck_04bf729d2a7e4f86` CHECK (position > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目版本选项与服务端答案';

CREATE TABLE `question_knowledge_links` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `question_revision_id` BIGINT NOT NULL COMMENT '题目版本',
  `knowledge_resource_id` BIGINT NOT NULL COMMENT '知识',
  `knowledge_revision_id` BIGINT NOT NULL COMMENT '知识版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_99416193e29f4874` (`question_revision_id`, `knowledge_resource_id`),
  KEY `ix_99416193e29f4874` (`knowledge_resource_id`),
  KEY `ix_f2bb0c1355fc14f2` (`knowledge_resource_id`, `knowledge_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目考查知识';

CREATE TABLE `learning_assessments` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `anchor_resource_id` BIGINT NOT NULL COMMENT '关联专题或卡片',
  `assessment_type` VARCHAR(20) NOT NULL COMMENT '测验类型',
  `version_no` INT NOT NULL COMMENT '版本号',
  `title` VARCHAR(255) NOT NULL COMMENT '名称',
  `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态',
  `pass_score` DECIMAL(8,2) NULL COMMENT '达标分数',
  `created_by` BIGINT NOT NULL COMMENT '创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_affe4292ad1d206b` (`anchor_resource_id`, `assessment_type`, `version_no`),
  KEY `ix_affe4292ad1d206b` (`created_by`),
  CONSTRAINT `ck_affe4292ad1d206b` CHECK (assessment_type IN ('PLACEMENT','CARD','TOPIC')),
  CONSTRAINT `ck_ed33bdf6e14debca` CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT `ck_90f185428b784d56` CHECK (version_no > 0),
  CONSTRAINT `ck_c2f7bafac32067ad` CHECK (pass_score IS NULL OR pass_score >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='测验模板版本，启用后固定题目';

CREATE TABLE `assessment_questions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `assessment_id` BIGINT NOT NULL COMMENT '测验',
  `question_resource_id` BIGINT NOT NULL COMMENT '题目',
  `question_revision_id` BIGINT NOT NULL COMMENT '题目版本',
  `position` INT NOT NULL COMMENT '顺序',
  `points` DECIMAL(8,2) NOT NULL COMMENT '分数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_629ce58efa938a12` (`assessment_id`, `position`),
  UNIQUE KEY `uk_33126f06a56d62a9` (`assessment_id`, `question_resource_id`),
  KEY `ix_629ce58efa938a12` (`question_revision_id`),
  KEY `ix_33126f06a56d62a9` (`question_resource_id`, `question_revision_id`),
  CONSTRAINT `ck_629ce58efa938a12` CHECK (position > 0),
  CONSTRAINT `ck_33126f06a56d62a9` CHECK (points > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模板题目固定版本';

CREATE TABLE `user_enrollments` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `topic_resource_id` BIGINT NOT NULL COMMENT '专题',
  `topic_revision_id` BIGINT NOT NULL COMMENT '专题版本',
  `last_card_resource_id` BIGINT NULL COMMENT '最近卡片',
  `status` VARCHAR(20) NOT NULL DEFAULT 'LEARNING' COMMENT '状态',
  `completed_at` DATETIME(3) NULL COMMENT '完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_1d3736452f547abc` (`user_id`, `topic_resource_id`),
  KEY `ix_1d3736452f547abc` (`user_id`, `updated_at`),
  KEY `ix_d14d83daa6caa108` (`topic_resource_id`),
  KEY `ix_b7241adbc4d244af` (`last_card_resource_id`),
  KEY `ix_8f6919f95d4d98cb` (`topic_resource_id`, `topic_revision_id`),
  CONSTRAINT `ck_1d3736452f547abc` CHECK (status IN ('LEARNING','COMPLETED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='专题学习，固定目录版本';

CREATE TABLE `user_card_progress` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `enrollment_id` BIGINT NOT NULL COMMENT '学习记录',
  `card_resource_id` BIGINT NOT NULL COMMENT '卡片',
  `card_revision_id` BIGINT NOT NULL COMMENT '实际学习版本',
  `status` VARCHAR(20) NOT NULL DEFAULT 'STARTED' COMMENT '状态',
  `completed_at` DATETIME(3) NULL COMMENT '明确完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_57f8808497b43171` (`enrollment_id`, `card_resource_id`),
  KEY `ix_57f8808497b43171` (`card_resource_id`),
  KEY `ix_07e6ed2bc06b5abf` (`card_resource_id`, `card_revision_id`),
  CONSTRAINT `ck_57f8808497b43171` CHECK (status IN ('STARTED','COMPLETED')),
  CONSTRAINT `ck_07e6ed2bc06b5abf` CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='明确完成标记，与测验成绩独立';

CREATE TABLE `quiz_attempts` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `assessment_id` BIGINT NOT NULL COMMENT '模板',
  `enrollment_id` BIGINT NULL COMMENT '专题记录',
  `request_key` CHAR(36) NOT NULL COMMENT '开始作答幂等键',
  `status` VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT '状态',
  `total_points` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '总分',
  `earned_points` DECIMAL(10,2) NULL COMMENT '得分',
  `submitted_at` DATETIME(3) NULL COMMENT '交卷时间',
  `lock_version` INT NOT NULL DEFAULT 0 COMMENT '并发控制',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_e87b9eb5ee3cba2b` (`user_id`, `request_key`),
  KEY `ix_e87b9eb5ee3cba2b` (`user_id`, `status`, `created_at`),
  KEY `ix_07211bf05347a77c` (`assessment_id`, `created_at`),
  KEY `ix_06027e8d4fad2113` (`enrollment_id`),
  CONSTRAINT `ck_e87b9eb5ee3cba2b` CHECK (status IN ('IN_PROGRESS','SUBMITTED','ABANDONED')),
  CONSTRAINT `ck_07211bf05347a77c` CHECK (total_points >= 0),
  CONSTRAINT `ck_06027e8d4fad2113` CHECK (earned_points IS NULL OR (earned_points >= 0 AND earned_points <= total_points)),
  CONSTRAINT `ck_bdb1ae986b86ffa7` CHECK (status <> 'SUBMITTED' OR (submitted_at IS NOT NULL AND earned_points IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次作答，交卷事务评分';

CREATE TABLE `quiz_attempt_items` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `attempt_id` BIGINT NOT NULL COMMENT '试卷',
  `question_resource_id` BIGINT NOT NULL COMMENT '题目',
  `question_revision_id` BIGINT NOT NULL COMMENT '题目版本',
  `position` INT NOT NULL COMMENT '顺序',
  `question_snapshot` JSON NOT NULL COMMENT '公开题干选项快照',
  `answer_key_snapshot` JSON NOT NULL COMMENT '仅服务端可读的标准答案与解析快照',
  `points` DECIMAL(8,2) NOT NULL COMMENT '满分',
  `earned_points` DECIMAL(8,2) NULL COMMENT '得分',
  `is_correct` TINYINT NULL COMMENT '正误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_c809b4d5116c32af` (`attempt_id`, `position`),
  UNIQUE KEY `uk_04e61a745d44c8d3` (`attempt_id`, `question_resource_id`),
  KEY `ix_c809b4d5116c32af` (`question_revision_id`),
  KEY `ix_04e61a745d44c8d3` (`question_resource_id`, `question_revision_id`),
  CONSTRAINT `ck_c809b4d5116c32af` CHECK (position > 0),
  CONSTRAINT `ck_04e61a745d44c8d3` CHECK (points > 0),
  CONSTRAINT `ck_9b387da524048bea` CHECK (earned_points IS NULL OR (earned_points >= 0 AND earned_points <= points)),
  CONSTRAINT `ck_b11543250bc92527` CHECK (is_correct IS NULL OR is_correct IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题干选项及私有答案快照';

CREATE TABLE `quiz_attempt_answers` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `attempt_item_id` BIGINT NOT NULL COMMENT '试卷题目',
  `selected_keys` JSON NOT NULL COMMENT '选择的选项键数组',
  `lock_version` INT NOT NULL DEFAULT 0 COMMENT '防旧请求覆盖',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_35c26d686ee14acc` (`attempt_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户暂存答案，交卷后冻结';

CREATE TABLE `user_wrong_questions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `question_resource_id` BIGINT NOT NULL COMMENT '题目',
  `last_wrong_item_id` BIGINT NOT NULL COMMENT '最近错误',
  `wrong_count` INT NOT NULL DEFAULT 1 COMMENT '错误次数',
  `last_wrong_at` DATETIME(3) NOT NULL COMMENT '最近错误',
  `review_state` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '复习状态',
  `last_reviewed_at` DATETIME(3) NULL COMMENT '最近复习',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_952ff6b605cdaa84` (`user_id`, `question_resource_id`),
  KEY `ix_952ff6b605cdaa84` (`user_id`, `review_state`, `last_wrong_at`),
  KEY `ix_701b0605985dee80` (`question_resource_id`),
  KEY `ix_e63da21c5e96a25a` (`last_wrong_item_id`),
  CONSTRAINT `ck_952ff6b605cdaa84` CHECK (wrong_count > 0),
  CONSTRAINT `ck_701b0605985dee80` CHECK (review_state IN ('PENDING','REVIEWED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='错题聚合，不覆盖作答历史';

CREATE TABLE `qa_sessions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `title` VARCHAR(160) NOT NULL COMMENT '会话名',
  `context_resource_id` BIGINT NULL COMMENT '初始场景',
  `archived_at` DATETIME(3) NULL COMMENT '删除归档时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_d2a4c8df7d032f04` (`user_id`, `updated_at`),
  KEY `ix_47e91422e151f630` (`context_resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私有多轮会话';

CREATE TABLE `qa_messages` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `session_id` BIGINT NOT NULL COMMENT '会话',
  `parent_message_id` BIGINT NULL COMMENT '回复消息',
  `role` VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT',
  `body` LONGTEXT NOT NULL COMMENT '消息内容',
  `status` VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' COMMENT '生成状态',
  `client_request_key` CHAR(36) NOT NULL COMMENT '会话内消息幂等键',
  `context_revision_id` BIGINT NULL COMMENT '场景版本',
  `refusal_reason` VARCHAR(40) NULL COMMENT '拒答原因分类',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ceb8d9052afaccd8` (`session_id`, `client_request_key`),
  UNIQUE KEY `uk_363c53d15d80b5b6` (`session_id`, `id`),
  KEY `ix_ceb8d9052afaccd8` (`session_id`, `created_at`),
  KEY `ix_363c53d15d80b5b6` (`context_revision_id`),
  KEY `ix_681a738cf2d3194f` (`session_id`, `parent_message_id`),
  CONSTRAINT `ck_ceb8d9052afaccd8` CHECK (role IN ('USER','ASSISTANT')),
  CONSTRAINT `ck_363c53d15d80b5b6` CHECK (status IN ('GENERATING','COMPLETED','STOPPED','FAILED','REFUSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问答消息，不存模型内部推理';

CREATE TABLE `qa_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `assistant_message_id` BIGINT NOT NULL COMMENT '助手消息',
  `route` VARCHAR(24) NOT NULL COMMENT '检索路径',
  `model_name` VARCHAR(100) NULL COMMENT '实际生成模型',
  `embedding_model` VARCHAR(100) NULL COMMENT '实际嵌入模型',
  `prompt_version` VARCHAR(40) NOT NULL COMMENT '模板版本',
  `retrieval_config` JSON NOT NULL COMMENT 'TopK、阈值、距离度量及索引代次',
  `latency_ms` INT NULL COMMENT '总耗时',
  `first_token_ms` INT NULL COMMENT '首内容token耗时',
  `input_tokens` INT NULL COMMENT '输入用量',
  `output_tokens` INT NULL COMMENT '输出用量',
  `cost_estimate` DECIMAL(12,6) NULL COMMENT '估算费用，非账单金额',
  `status` VARCHAR(20) NOT NULL COMMENT '运行状态',
  `error_code` VARCHAR(80) NULL COMMENT '失败分类',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_9689ec541dcdf330` (`assistant_message_id`),
  CONSTRAINT `ck_9689ec541dcdf330` CHECK (route IN ('VECTOR','STRUCTURED','GRAPH','HYBRID','REFUSE')),
  CONSTRAINT `ck_a77b5463c55bf0fd` CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','STOPPED','REFUSED')),
  CONSTRAINT `ck_bcbf629f60d5709b` CHECK (latency_ms IS NULL OR latency_ms >= 0),
  CONSTRAINT `ck_c26461bf7d14acb9` CHECK (first_token_ms IS NULL OR first_token_ms >= 0),
  CONSTRAINT `ck_78ee79cc9179f5c0` CHECK (input_tokens IS NULL OR input_tokens >= 0),
  CONSTRAINT `ck_6327f023c27b850a` CHECK (output_tokens IS NULL OR output_tokens >= 0),
  CONSTRAINT `ck_cdad49695fdbc06a` CHECK (cost_estimate IS NULL OR cost_estimate >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检索生成配置、耗时和用量';

CREATE TABLE `qa_retrieval_hits` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `run_id` BIGINT NOT NULL COMMENT '运行',
  `rank_no` INT NOT NULL COMMENT '排名',
  `channel` VARCHAR(20) NOT NULL COMMENT '检索渠道',
  `revision_id` BIGINT NULL COMMENT '知识版本',
  `segment_id` BIGINT NULL COMMENT '来源段落',
  `raw_metric` DECIMAL(14,8) NULL COMMENT '原始距离或相似度',
  `metric_name` VARCHAR(40) NULL COMMENT '度量名称',
  `normalized_score` DECIMAL(10,8) NULL COMMENT '标准化排序分，非正确概率',
  `content_snapshot` TEXT NOT NULL COMMENT '当时证据内容',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_c12dd672a6506526` (`run_id`, `rank_no`),
  KEY `ix_c12dd672a6506526` (`revision_id`),
  KEY `ix_5f179af1fad505c9` (`segment_id`),
  CONSTRAINT `ck_c12dd672a6506526` CHECK (rank_no > 0),
  CONSTRAINT `ck_5f179af1fad505c9` CHECK (revision_id IS NOT NULL OR segment_id IS NOT NULL),
  CONSTRAINT `ck_c1845b218528eb67` CHECK (channel IN ('VECTOR','STRUCTURED','GRAPH')),
  CONSTRAINT `ck_8fb61396e2a7ddf7` CHECK (normalized_score IS NULL OR (normalized_score >= 0 AND normalized_score <= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检索证据快照，区分距离和相似度';

CREATE TABLE `qa_citations` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `message_id` BIGINT NOT NULL COMMENT '回答',
  `citation_no` INT NOT NULL COMMENT '引用序号',
  `retrieval_hit_id` BIGINT NOT NULL COMMENT '实际检索证据',
  `revision_id` BIGINT NULL COMMENT '知识版本',
  `segment_id` BIGINT NULL COMMENT '来源段落',
  `title_snapshot` VARCHAR(255) NOT NULL COMMENT '当时标题',
  `quote_snapshot` TEXT NOT NULL COMMENT '当时引用片段',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_6eaae2551baea12e` (`message_id`, `citation_no`),
  KEY `ix_6eaae2551baea12e` (`retrieval_hit_id`),
  KEY `ix_98d2acb211c0cb51` (`revision_id`),
  KEY `ix_f4980350ec9593bf` (`segment_id`),
  CONSTRAINT `ck_6eaae2551baea12e` CHECK (citation_no > 0),
  CONSTRAINT `ck_98d2acb211c0cb51` CHECK (revision_id IS NOT NULL OR segment_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='引用快照保留原始证据';

CREATE TABLE `qa_feedback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '反馈者',
  `message_id` BIGINT NOT NULL COMMENT '回答',
  `rating` VARCHAR(20) NOT NULL COMMENT '评价',
  `comment` TEXT NULL COMMENT '说明',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ae55931306bf26f3` (`user_id`, `message_id`),
  KEY `ix_ae55931306bf26f3` (`message_id`),
  CONSTRAINT `ck_ae55931306bf26f3` CHECK (rating IN ('HELPFUL','UNHELPFUL','INCORRECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='质量反馈';

CREATE TABLE `qa_saved_answers` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `message_id` BIGINT NOT NULL COMMENT '自己的回答',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_2e5571b2bcf16cf9` (`user_id`, `message_id`),
  KEY `ix_2e5571b2bcf16cf9` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私有回答收藏';

CREATE TABLE `user_tag_preferences` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `tag_id` BIGINT NOT NULL COMMENT '标签',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_c0a298cdd74ef497` (`user_id`, `tag_id`),
  KEY `ix_c0a298cdd74ef497` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户主动选择的兴趣';

CREATE TABLE `user_interest_scores` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `tag_id` BIGINT NOT NULL COMMENT '标签',
  `score` DECIMAL(14,6) NOT NULL COMMENT '衰减分',
  `calculated_at` DATETIME(3) NOT NULL COMMENT '计算时间',
  `algorithm_version` VARCHAR(40) NOT NULL COMMENT '算法版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_64d2142225b5f3af` (`user_id`, `tag_id`),
  KEY `ix_64d2142225b5f3af` (`tag_id`),
  CONSTRAINT `ck_64d2142225b5f3af` CHECK (score >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可重建的行为画像';

CREATE TABLE `recommendation_strategies` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `scene` VARCHAR(32) NOT NULL COMMENT '场景',
  `version_no` INT NOT NULL COMMENT '配置版本',
  `name` VARCHAR(100) NOT NULL COMMENT '名称',
  `config` JSON NOT NULL COMMENT '权重、去重、多样性等参数',
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态',
  `active_scene` VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN scene ELSE NULL END) STORED COMMENT '有效配置唯一键',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_55b8099b46371786` (`scene`, `version_no`),
  UNIQUE KEY `uk_d5e8c795d2add8aa` (`active_scene`),
  CONSTRAINT `ck_55b8099b46371786` CHECK (version_no > 0),
  CONSTRAINT `ck_d5e8c795d2add8aa` CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT `ck_75d40f122ccf2650` CHECK (scene IN ('DISCOVER','CONTINUE_LEARNING','REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每场景只允许一个启用策略';

CREATE TABLE `recommendation_requests` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `strategy_id` BIGINT NOT NULL COMMENT '策略版本',
  `request_key` CHAR(36) NOT NULL COMMENT '幂等键',
  `scene` VARCHAR(32) NOT NULL COMMENT '入口',
  `context_resource_id` BIGINT NULL COMMENT '当前内容',
  `experiment_group` VARCHAR(40) NULL COMMENT '预分配实验组',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_45e8f14f9f96986c` (`user_id`, `request_key`),
  KEY `ix_45e8f14f9f96986c` (`user_id`, `created_at`),
  KEY `ix_da3e5e06da3524f5` (`strategy_id`, `created_at`),
  KEY `ix_99a274ef2e31ab35` (`context_resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='返回不等同曝光';

CREATE TABLE `recommendation_items` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `request_id` BIGINT NOT NULL COMMENT '批次',
  `resource_id` BIGINT NOT NULL COMMENT '内容',
  `revision_id` BIGINT NOT NULL COMMENT '当时正式版本',
  `position` INT NOT NULL COMMENT '位置',
  `score` DECIMAL(14,6) NOT NULL COMMENT '最终分',
  `component_scores` JSON NOT NULL COMMENT '各路分和有效权重',
  `reason_code` VARCHAR(40) NOT NULL COMMENT '理由编码',
  `reason_text` VARCHAR(255) NOT NULL COMMENT '理由文案',
  `exposed_at` DATETIME(3) NULL COMMENT '实际可见确认',
  `first_clicked_at` DATETIME(3) NULL COMMENT '首次点击',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_1d79e72e512a15d2` (`request_id`, `position`),
  UNIQUE KEY `uk_8693b471e8afb359` (`request_id`, `resource_id`),
  KEY `ix_1d79e72e512a15d2` (`resource_id`),
  KEY `ix_8693b471e8afb359` (`resource_id`, `revision_id`),
  CONSTRAINT `ck_1d79e72e512a15d2` CHECK (position > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推荐内容、理由和实际曝光';

CREATE TABLE `recommendation_feedback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `user_id` BIGINT NOT NULL COMMENT '用户',
  `resource_id` BIGINT NOT NULL COMMENT '内容',
  `feedback_type` VARCHAR(24) NOT NULL COMMENT '反馈类型',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_10283fb47d964336` (`user_id`, `resource_id`),
  KEY `ix_10283fb47d964336` (`resource_id`),
  CONSTRAINT `ck_10283fb47d964336` CHECK (feedback_type IN ('NOT_INTERESTED','HIDE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='显式负反馈';

CREATE TABLE `behavior_events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `event_key` CHAR(36) NOT NULL COMMENT '事件幂等键',
  `user_id` BIGINT NULL COMMENT '登录用户',
  `anonymous_session` CHAR(36) NULL COMMENT '短期匿名标识',
  `resource_id` BIGINT NULL COMMENT '内容目标',
  `quiz_attempt_id` BIGINT NULL COMMENT '测验目标',
  `recommendation_item_id` BIGINT NULL COMMENT '归因推荐项',
  `behavior_type` VARCHAR(32) NOT NULL COMMENT '行为类型',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '服务端确认时间',
  `metadata` JSON NULL COMMENT '停留时间等补充属性',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_6e6c7d607ef31830` (`event_key`),
  KEY `ix_6e6c7d607ef31830` (`user_id`, `occurred_at`),
  KEY `ix_122000f34199255d` (`resource_id`, `behavior_type`, `occurred_at`),
  KEY `ix_6b35f37d5010de4f` (`quiz_attempt_id`),
  KEY `ix_ea9a7e72255ddfee` (`recommendation_item_id`),
  CONSTRAINT `ck_6e6c7d607ef31830` CHECK (user_id IS NOT NULL OR anonymous_session IS NOT NULL),
  CONSTRAINT `ck_122000f34199255d` CHECK (resource_id IS NOT NULL OR quiz_attempt_id IS NOT NULL),
  CONSTRAINT `ck_6b35f37d5010de4f` CHECK (behavior_type IN ('VIEW','LIKE','UNLIKE','BOOKMARK','UNBOOKMARK','COMMENT','PUBLISH','COMPLETE_CARD','SUBMIT_QUIZ','RECOMMEND_CLICK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='幂等事件；匿名浏览只计热度';

CREATE TABLE `community_sections` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `name` VARCHAR(80) NOT NULL COMMENT '名称',
  `code` VARCHAR(64) NOT NULL COMMENT '编码',
  `description` VARCHAR(500) NULL COMMENT '介绍',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '顺序',
  `active` TINYINT NOT NULL DEFAULT 1 COMMENT '启用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_36b0eca49b47853c` (`code`),
  CONSTRAINT `ck_36b0eca49b47853c` CHECK (active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分区';

CREATE TABLE `community_post_profiles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `resource_id` BIGINT NOT NULL COMMENT 'POST或ARTICLE资源',
  `section_id` BIGINT NOT NULL COMMENT '分区',
  `is_pinned` TINYINT NOT NULL DEFAULT 0 COMMENT '置顶',
  `comments_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '允许评论',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_e00d24c3f982380a` (`resource_id`),
  KEY `ix_e00d24c3f982380a` (`section_id`),
  CONSTRAINT `ck_e00d24c3f982380a` CHECK (is_pinned IN (0,1)),
  CONSTRAINT `ck_9038a906c5105edb` CHECK (comments_enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子文章身份，正文采用共用版本';

CREATE TABLE `community_knowledge_links` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `post_revision_id` BIGINT NOT NULL COMMENT '文章或帖子版本',
  `knowledge_resource_id` BIGINT NOT NULL COMMENT '知识',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_4a3d19434885e280` (`post_revision_id`, `knowledge_resource_id`),
  KEY `ix_4a3d19434885e280` (`knowledge_resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容版本关联知识';

CREATE TABLE `community_comments` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `resource_id` BIGINT NOT NULL COMMENT '帖子文章',
  `user_id` BIGINT NOT NULL COMMENT '作者',
  `parent_comment_id` BIGINT NULL COMMENT '被回复评论',
  `body` TEXT NOT NULL COMMENT '内容',
  `status` VARCHAR(16) NOT NULL DEFAULT 'VISIBLE' COMMENT '状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_7c86f05eee7c551b` (`resource_id`, `id`),
  KEY `ix_7c86f05eee7c551b` (`resource_id`, `status`, `created_at`),
  KEY `ix_837fb232147e99c2` (`user_id`),
  KEY `ix_387b8bbd233a8bb5` (`resource_id`, `parent_comment_id`),
  CONSTRAINT `ck_7c86f05eee7c551b` CHECK (status IN ('VISIBLE','HIDDEN','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有限层级回复，服务层限制仅回复根评论';

CREATE TABLE `community_reports` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `reporter_id` BIGINT NOT NULL COMMENT '举报人',
  `resource_id` BIGINT NULL COMMENT '帖子文章',
  `comment_id` BIGINT NULL COMMENT '评论',
  `reason_code` VARCHAR(40) NOT NULL COMMENT '原因',
  `description` TEXT NULL COMMENT '补充说明',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态',
  `handler_id` BIGINT NULL COMMENT '处理人',
  `resolution` TEXT NULL COMMENT '结果',
  `resolved_at` DATETIME(3) NULL COMMENT '处理时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_22dc97549865582b` (`status`, `created_at`),
  KEY `ix_f772403f66155a4b` (`reporter_id`, `created_at`),
  KEY `ix_3fd5c899235a378e` (`resource_id`),
  KEY `ix_59880dae4e79f4c6` (`comment_id`),
  KEY `ix_c0046d0bb2cc36f1` (`handler_id`),
  CONSTRAINT `ck_22dc97549865582b` CHECK ((resource_id IS NOT NULL) + (comment_id IS NOT NULL) = 1),
  CONSTRAINT `ck_f772403f66155a4b` CHECK (status IN ('PENDING','PROCESSING','RESOLVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='举报内容或评论，二选一';

CREATE TABLE `community_moderation_actions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键；API按字符串返回',
  `report_id` BIGINT NULL COMMENT '举报',
  `resource_id` BIGINT NULL COMMENT '帖子文章',
  `comment_id` BIGINT NULL COMMENT '评论',
  `actor_id` BIGINT NOT NULL COMMENT '操作者',
  `action` VARCHAR(32) NOT NULL COMMENT '动作',
  `reason` TEXT NOT NULL COMMENT '依据',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  KEY `ix_ebca9bf3e351fc8a` (`resource_id`, `created_at`),
  KEY `ix_e1df2f69e62b7723` (`comment_id`, `created_at`),
  KEY `ix_11d10607c44335e2` (`report_id`),
  KEY `ix_04d51b2c7a8775eb` (`actor_id`),
  CONSTRAINT `ck_ebca9bf3e351fc8a` CHECK ((resource_id IS NOT NULL) + (comment_id IS NOT NULL) = 1),
  CONSTRAINT `ck_e1df2f69e62b7723` CHECK (action IN ('HIDE','RESTORE','SUSPEND','DISABLE_COMMENTS','DISMISS_REPORT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容治理操作历史';

ALTER TABLE `sys_user_roles` ADD CONSTRAINT `fk_75fce77299278151` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_user_roles` ADD CONSTRAINT `fk_d3ef7732f6325f11` FOREIGN KEY (`role_id`) REFERENCES `sys_roles` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_user_roles` ADD CONSTRAINT `fk_0180ee2e940dfaea` FOREIGN KEY (`granted_by`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_role_permissions` ADD CONSTRAINT `fk_d868386ce40239e8` FOREIGN KEY (`role_id`) REFERENCES `sys_roles` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_role_permissions` ADD CONSTRAINT `fk_a77153509b0660c4` FOREIGN KEY (`permission_id`) REFERENCES `sys_permissions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_role_applications` ADD CONSTRAINT `fk_51f8d202122aa314` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_role_applications` ADD CONSTRAINT `fk_aed62e1910af4123` FOREIGN KEY (`role_id`) REFERENCES `sys_roles` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_role_applications` ADD CONSTRAINT `fk_74a4bc1e7a94bb80` FOREIGN KEY (`reviewer_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `auth_refresh_tokens` ADD CONSTRAINT `fk_b1a042478b8aa01f` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `auth_refresh_tokens` ADD CONSTRAINT `fk_83ffccf51b02d070` FOREIGN KEY (`replaced_by_id`) REFERENCES `auth_refresh_tokens` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `file_objects` ADD CONSTRAINT `fk_3476db561393a3a1` FOREIGN KEY (`uploaded_by`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `content_categories` ADD CONSTRAINT `fk_72752d00a6f82e39` FOREIGN KEY (`parent_id`) REFERENCES `content_categories` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `content_resources` ADD CONSTRAINT `fk_c511b599a30525c0` FOREIGN KEY (`owner_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `content_revisions` ADD CONSTRAINT `fk_2e91f8749d4c924a` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `content_revisions` ADD CONSTRAINT `fk_f1225b967c6dcb3a` FOREIGN KEY (`category_id`) REFERENCES `content_categories` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `content_revisions` ADD CONSTRAINT `fk_bcd0e7ddd8546f75` FOREIGN KEY (`created_by`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `revision_tags` ADD CONSTRAINT `fk_72c227454fe9b0a5` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `revision_tags` ADD CONSTRAINT `fk_3a7cfbd4e856bc8c` FOREIGN KEY (`tag_id`) REFERENCES `content_tags` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_bookmarks` ADD CONSTRAINT `fk_21bf353012f0f276` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_bookmarks` ADD CONSTRAINT `fk_c904629fec8d1895` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_likes` ADD CONSTRAINT `fk_ad57eeee88cba5cf` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_likes` ADD CONSTRAINT `fk_b5441f67311669f9` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_resource_history` ADD CONSTRAINT `fk_a482f67d51d99aaf` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_resource_history` ADD CONSTRAINT `fk_865b7fcf636a85dc` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_feedback` ADD CONSTRAINT `fk_c293b190e1d7f1b0` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_feedback` ADD CONSTRAINT `fk_fa98673bc24a7085` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_feedback` ADD CONSTRAINT `fk_82f02de9c0391ebb` FOREIGN KEY (`handler_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_notifications` ADD CONSTRAINT `fk_81892e7ced68dac7` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_notifications` ADD CONSTRAINT `fk_705adddf6626c33d` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_audit_logs` ADD CONSTRAINT `fk_17e9ef62e0fe3b03` FOREIGN KEY (`actor_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `source_documents` ADD CONSTRAINT `fk_5deccb53dcff2a57` FOREIGN KEY (`file_id`) REFERENCES `file_objects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `source_documents` ADD CONSTRAINT `fk_c4ef46565ed25120` FOREIGN KEY (`origin_revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `source_documents` ADD CONSTRAINT `fk_bb682ef1d24cff02` FOREIGN KEY (`parent_document_id`) REFERENCES `source_documents` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `source_documents` ADD CONSTRAINT `fk_f0ea68969d2f2c98` FOREIGN KEY (`submitted_by`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `document_parse_runs` ADD CONSTRAINT `fk_bc99691e2616d3d9` FOREIGN KEY (`document_id`) REFERENCES `source_documents` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `revision_evidence` ADD CONSTRAINT `fk_260a3ef0d93e0a2f` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `revision_evidence` ADD CONSTRAINT `fk_aa7b834e7a61c338` FOREIGN KEY (`document_id`) REFERENCES `source_documents` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `review_tasks` ADD CONSTRAINT `fk_4305723606afcce4` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `review_tasks` ADD CONSTRAINT `fk_19af74b88e74a65d` FOREIGN KEY (`document_id`) REFERENCES `source_documents` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `review_tasks` ADD CONSTRAINT `fk_3cac06596f77a3d2` FOREIGN KEY (`submitted_by`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `review_tasks` ADD CONSTRAINT `fk_ed6216b53b6c4e9e` FOREIGN KEY (`reviewer_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `review_events` ADD CONSTRAINT `fk_fbab91d2db65d614` FOREIGN KEY (`review_task_id`) REFERENCES `review_tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `review_events` ADD CONSTRAINT `fk_2f42f0439e07eee1` FOREIGN KEY (`actor_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `async_jobs` ADD CONSTRAINT `fk_0710065121a96a7e` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `async_jobs` ADD CONSTRAINT `fk_ae1455c7bd7e090f` FOREIGN KEY (`document_id`) REFERENCES `source_documents` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `async_job_attempts` ADD CONSTRAINT `fk_c9d9cdd2769dc713` FOREIGN KEY (`job_id`) REFERENCES `async_jobs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_entries` ADD CONSTRAINT `fk_74643adf85d94bf4` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_aliases` ADD CONSTRAINT `fk_f66a347d63c1f954` FOREIGN KEY (`knowledge_resource_id`) REFERENCES `knowledge_entries` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `herb_profiles` ADD CONSTRAINT `fk_7254be3085739561` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `formula_profiles` ADD CONSTRAINT `fk_59dd860819e5b7ea` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `formula_ingredients` ADD CONSTRAINT `fk_2d85c5ab32d82bb1` FOREIGN KEY (`formula_resource_id`) REFERENCES `knowledge_entries` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `formula_ingredients` ADD CONSTRAINT `fk_3f4e8ba5fc77e1dc` FOREIGN KEY (`herb_resource_id`) REFERENCES `knowledge_entries` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_relations` ADD CONSTRAINT `fk_4190fdb4b101a9b8` FOREIGN KEY (`source_resource_id`) REFERENCES `knowledge_entries` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_relations` ADD CONSTRAINT `fk_1956088a7614c314` FOREIGN KEY (`target_resource_id`) REFERENCES `knowledge_entries` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `learning_topics` ADD CONSTRAINT `fk_3468334d8b0c1234` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `topic_profiles` ADD CONSTRAINT `fk_027f32e180c60f6f` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `learning_cards` ADD CONSTRAINT `fk_ded9547aef3b17ba` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `topic_cards` ADD CONSTRAINT `fk_d18564f4d1cbc5ce` FOREIGN KEY (`topic_revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `topic_cards` ADD CONSTRAINT `fk_86de977ff499a1f9` FOREIGN KEY (`card_resource_id`) REFERENCES `learning_cards` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `card_knowledge_links` ADD CONSTRAINT `fk_c3e4c4e27dc48635` FOREIGN KEY (`card_revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `card_knowledge_links` ADD CONSTRAINT `fk_503ed88b4a91c66b` FOREIGN KEY (`knowledge_resource_id`) REFERENCES `knowledge_entries` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `question_profiles` ADD CONSTRAINT `fk_ad7106c1fbcdd4a9` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `question_options` ADD CONSTRAINT `fk_d56750d9682faa3b` FOREIGN KEY (`question_revision_id`) REFERENCES `question_profiles` (`revision_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `question_knowledge_links` ADD CONSTRAINT `fk_a0e4ec824c5d5164` FOREIGN KEY (`question_revision_id`) REFERENCES `question_profiles` (`revision_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `question_knowledge_links` ADD CONSTRAINT `fk_8f2d0cb7d0599f6a` FOREIGN KEY (`knowledge_resource_id`) REFERENCES `knowledge_entries` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `learning_assessments` ADD CONSTRAINT `fk_cdda0fb460313f4c` FOREIGN KEY (`anchor_resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `learning_assessments` ADD CONSTRAINT `fk_543238aca76117ba` FOREIGN KEY (`created_by`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `assessment_questions` ADD CONSTRAINT `fk_195dac8fdd747cf4` FOREIGN KEY (`assessment_id`) REFERENCES `learning_assessments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `assessment_questions` ADD CONSTRAINT `fk_9b5a7470520534d6` FOREIGN KEY (`question_revision_id`) REFERENCES `question_profiles` (`revision_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_enrollments` ADD CONSTRAINT `fk_3190b2c7174ebe1d` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_enrollments` ADD CONSTRAINT `fk_ed7724bcc3748e78` FOREIGN KEY (`topic_resource_id`) REFERENCES `learning_topics` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_enrollments` ADD CONSTRAINT `fk_152e30477741ee1e` FOREIGN KEY (`last_card_resource_id`) REFERENCES `learning_cards` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_card_progress` ADD CONSTRAINT `fk_216b779949eed7dd` FOREIGN KEY (`enrollment_id`) REFERENCES `user_enrollments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_card_progress` ADD CONSTRAINT `fk_1183e172004ee118` FOREIGN KEY (`card_resource_id`) REFERENCES `learning_cards` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quiz_attempts` ADD CONSTRAINT `fk_6c8543856a010b64` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quiz_attempts` ADD CONSTRAINT `fk_ca07d6e33c62a198` FOREIGN KEY (`assessment_id`) REFERENCES `learning_assessments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quiz_attempts` ADD CONSTRAINT `fk_9c4861922f1d35a5` FOREIGN KEY (`enrollment_id`) REFERENCES `user_enrollments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quiz_attempt_items` ADD CONSTRAINT `fk_424abb3f42eb4d9b` FOREIGN KEY (`attempt_id`) REFERENCES `quiz_attempts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quiz_attempt_items` ADD CONSTRAINT `fk_e0b2eddd5ac9e917` FOREIGN KEY (`question_revision_id`) REFERENCES `question_profiles` (`revision_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quiz_attempt_answers` ADD CONSTRAINT `fk_06d2e2cedce334b3` FOREIGN KEY (`attempt_item_id`) REFERENCES `quiz_attempt_items` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_wrong_questions` ADD CONSTRAINT `fk_a9b18524b40b5208` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_wrong_questions` ADD CONSTRAINT `fk_7696a680c92be5ce` FOREIGN KEY (`question_resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_wrong_questions` ADD CONSTRAINT `fk_e0ac03f9a6ef24b8` FOREIGN KEY (`last_wrong_item_id`) REFERENCES `quiz_attempt_items` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_sessions` ADD CONSTRAINT `fk_cad8072e3381cdd6` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_sessions` ADD CONSTRAINT `fk_b55e687535b68cd3` FOREIGN KEY (`context_resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_messages` ADD CONSTRAINT `fk_167a2d1bea80937f` FOREIGN KEY (`session_id`) REFERENCES `qa_sessions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_messages` ADD CONSTRAINT `fk_21154d4c37bbbdc8` FOREIGN KEY (`context_revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_runs` ADD CONSTRAINT `fk_842f93e687c3f389` FOREIGN KEY (`assistant_message_id`) REFERENCES `qa_messages` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_retrieval_hits` ADD CONSTRAINT `fk_d5a4d3477eb5e7a5` FOREIGN KEY (`run_id`) REFERENCES `qa_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_retrieval_hits` ADD CONSTRAINT `fk_763eb869f4c3de56` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_retrieval_hits` ADD CONSTRAINT `fk_c6fd4317cb2e9d66` FOREIGN KEY (`segment_id`) REFERENCES `document_segments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_citations` ADD CONSTRAINT `fk_6e8e85fa168e9331` FOREIGN KEY (`message_id`) REFERENCES `qa_messages` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_citations` ADD CONSTRAINT `fk_07dddd542556d33f` FOREIGN KEY (`retrieval_hit_id`) REFERENCES `qa_retrieval_hits` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_citations` ADD CONSTRAINT `fk_66f6e1d60b43a571` FOREIGN KEY (`revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_citations` ADD CONSTRAINT `fk_4551cef9a6d3d451` FOREIGN KEY (`segment_id`) REFERENCES `document_segments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_feedback` ADD CONSTRAINT `fk_c3ecc4b74c835a78` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_feedback` ADD CONSTRAINT `fk_f55e9aadbfa19019` FOREIGN KEY (`message_id`) REFERENCES `qa_messages` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_saved_answers` ADD CONSTRAINT `fk_611f5fbc7b539bd5` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_saved_answers` ADD CONSTRAINT `fk_d05e281101c4b889` FOREIGN KEY (`message_id`) REFERENCES `qa_messages` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_tag_preferences` ADD CONSTRAINT `fk_9bddcd1d99228fd9` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_tag_preferences` ADD CONSTRAINT `fk_0fa238642c4777c8` FOREIGN KEY (`tag_id`) REFERENCES `content_tags` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_interest_scores` ADD CONSTRAINT `fk_b92f0fd448f18c74` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_interest_scores` ADD CONSTRAINT `fk_9cec199604454c73` FOREIGN KEY (`tag_id`) REFERENCES `content_tags` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `recommendation_requests` ADD CONSTRAINT `fk_40cad90993293a90` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `recommendation_requests` ADD CONSTRAINT `fk_eb33d1a67a589422` FOREIGN KEY (`strategy_id`) REFERENCES `recommendation_strategies` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `recommendation_requests` ADD CONSTRAINT `fk_d91f4503436867af` FOREIGN KEY (`context_resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `recommendation_items` ADD CONSTRAINT `fk_f254acd8a5ddde94` FOREIGN KEY (`request_id`) REFERENCES `recommendation_requests` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `recommendation_items` ADD CONSTRAINT `fk_24aad7bc98b5f94f` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `recommendation_feedback` ADD CONSTRAINT `fk_cf4a190099c03ebc` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `recommendation_feedback` ADD CONSTRAINT `fk_c34addd67d2cd7ea` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `behavior_events` ADD CONSTRAINT `fk_9369deab2974be28` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `behavior_events` ADD CONSTRAINT `fk_ce6b0ba48cee4bba` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `behavior_events` ADD CONSTRAINT `fk_18e93e8abd130942` FOREIGN KEY (`quiz_attempt_id`) REFERENCES `quiz_attempts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `behavior_events` ADD CONSTRAINT `fk_ae1f3217b6281705` FOREIGN KEY (`recommendation_item_id`) REFERENCES `recommendation_items` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_post_profiles` ADD CONSTRAINT `fk_bd91321f53cf1292` FOREIGN KEY (`resource_id`) REFERENCES `content_resources` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_post_profiles` ADD CONSTRAINT `fk_5cc84b745ef064da` FOREIGN KEY (`section_id`) REFERENCES `community_sections` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_knowledge_links` ADD CONSTRAINT `fk_004388c3c3edb167` FOREIGN KEY (`post_revision_id`) REFERENCES `content_revisions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_knowledge_links` ADD CONSTRAINT `fk_4f627bda56b68aaa` FOREIGN KEY (`knowledge_resource_id`) REFERENCES `knowledge_entries` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_comments` ADD CONSTRAINT `fk_d3883262a2749909` FOREIGN KEY (`resource_id`) REFERENCES `community_post_profiles` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_comments` ADD CONSTRAINT `fk_5d892a2ff443df73` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_reports` ADD CONSTRAINT `fk_b9adcdad588afe76` FOREIGN KEY (`reporter_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_reports` ADD CONSTRAINT `fk_0fe94820505df837` FOREIGN KEY (`resource_id`) REFERENCES `community_post_profiles` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_reports` ADD CONSTRAINT `fk_c5dc50c116afc4f8` FOREIGN KEY (`comment_id`) REFERENCES `community_comments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_reports` ADD CONSTRAINT `fk_68fedd97f2d54b28` FOREIGN KEY (`handler_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_moderation_actions` ADD CONSTRAINT `fk_c0954694910b3d7f` FOREIGN KEY (`report_id`) REFERENCES `community_reports` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_moderation_actions` ADD CONSTRAINT `fk_c15c1e38b46f48e2` FOREIGN KEY (`resource_id`) REFERENCES `community_post_profiles` (`resource_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_moderation_actions` ADD CONSTRAINT `fk_ec70a22995725a0f` FOREIGN KEY (`comment_id`) REFERENCES `community_comments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_moderation_actions` ADD CONSTRAINT `fk_221cc63491a49aee` FOREIGN KEY (`actor_id`) REFERENCES `sys_users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `content_resources` ADD CONSTRAINT `fk_ec796316167eebca` FOREIGN KEY (`id`, `published_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `content_revisions` ADD CONSTRAINT `fk_3fce9f3355570e9d` FOREIGN KEY (`resource_id`, `base_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_feedback` ADD CONSTRAINT `fk_de34813ce567dde9` FOREIGN KEY (`resource_id`, `revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `document_segments` ADD CONSTRAINT `fk_d9864e3003d0d253` FOREIGN KEY (`document_id`, `parse_run_id`) REFERENCES `document_parse_runs` (`document_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `revision_evidence` ADD CONSTRAINT `fk_dcb77845f6ee70b0` FOREIGN KEY (`document_id`, `segment_id`) REFERENCES `document_segments` (`document_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `formula_ingredients` ADD CONSTRAINT `fk_86202727819b6373` FOREIGN KEY (`formula_resource_id`, `formula_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `formula_ingredients` ADD CONSTRAINT `fk_e6cebd10c7b66f2b` FOREIGN KEY (`herb_resource_id`, `herb_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_relations` ADD CONSTRAINT `fk_e9f781f4a4cea57a` FOREIGN KEY (`source_resource_id`, `source_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_relations` ADD CONSTRAINT `fk_3ef61fdcecee9cce` FOREIGN KEY (`target_resource_id`, `target_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `topic_cards` ADD CONSTRAINT `fk_9f02c00c0a5fc97f` FOREIGN KEY (`card_resource_id`, `card_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `card_knowledge_links` ADD CONSTRAINT `fk_17f391335e9298d2` FOREIGN KEY (`knowledge_resource_id`, `knowledge_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `question_knowledge_links` ADD CONSTRAINT `fk_2641e21ce63f0436` FOREIGN KEY (`knowledge_resource_id`, `knowledge_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `assessment_questions` ADD CONSTRAINT `fk_71ddebdfa4436769` FOREIGN KEY (`question_resource_id`, `question_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_enrollments` ADD CONSTRAINT `fk_c9d5e1027b795671` FOREIGN KEY (`topic_resource_id`, `topic_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_card_progress` ADD CONSTRAINT `fk_df0f1cf55c00a64a` FOREIGN KEY (`card_resource_id`, `card_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quiz_attempt_items` ADD CONSTRAINT `fk_c869d0e23d8d7a2e` FOREIGN KEY (`question_resource_id`, `question_revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `recommendation_items` ADD CONSTRAINT `fk_30def3e0ba7c8d11` FOREIGN KEY (`resource_id`, `revision_id`) REFERENCES `content_revisions` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `formula_ingredients` ADD CONSTRAINT `fk_a2575f862d3186c8` FOREIGN KEY (`formula_revision_id`, `evidence_id`) REFERENCES `revision_evidence` (`revision_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_relations` ADD CONSTRAINT `fk_89d4c967b8ebe579` FOREIGN KEY (`source_revision_id`, `evidence_id`) REFERENCES `revision_evidence` (`revision_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `qa_messages` ADD CONSTRAINT `fk_13fef9928b97cb91` FOREIGN KEY (`session_id`, `parent_message_id`) REFERENCES `qa_messages` (`session_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `community_comments` ADD CONSTRAINT `fk_c94dda97ed527d8b` FOREIGN KEY (`resource_id`, `parent_comment_id`) REFERENCES `community_comments` (`resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
