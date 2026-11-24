-- Community exceptions and write guarantees. No existing source/knowledge tables are changed.
CREATE TABLE community_quota_requests (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 resource_id BIGINT NOT NULL,requested_by BIGINT NOT NULL,requested_limit INT NOT NULL,
 reason VARCHAR(2000) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 reviewer_id BIGINT NULL,approved_limit INT NULL,review_note VARCHAR(2000) NULL,reviewed_at DATETIME(3) NULL,
 lock_version INT NOT NULL DEFAULT 0,
 pending_slot BIGINT GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN resource_id ELSE NULL END) STORED,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_quota_pending(pending_slot),KEY ix_quota_request_user(requested_by,id),KEY ix_quota_request_status(status,id),
 CONSTRAINT fk_quota_request_resource FOREIGN KEY(resource_id) REFERENCES content_resources(id),
 CONSTRAINT fk_quota_request_author FOREIGN KEY(requested_by) REFERENCES sys_users(id),
 CONSTRAINT fk_quota_request_reviewer FOREIGN KEY(reviewer_id) REFERENCES sys_users(id),
 CONSTRAINT ck_quota_request_state CHECK(status IN('PENDING','APPROVED','REJECTED','WITHDRAWN')),
 CONSTRAINT ck_quota_request_limit CHECK(requested_limit>0),
 CONSTRAINT ck_quota_approved_limit CHECK(approved_limit IS NULL OR approved_limit>0),
 CONSTRAINT ck_quota_request_lock CHECK(lock_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE community_article_quotas (
 resource_id BIGINT NOT NULL PRIMARY KEY,body_char_limit INT NOT NULL,last_request_id BIGINT NOT NULL,
 lock_version INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_article_quota_resource FOREIGN KEY(resource_id) REFERENCES content_resources(id),
 CONSTRAINT fk_article_quota_request FOREIGN KEY(last_request_id) REFERENCES community_quota_requests(id),
 CONSTRAINT ck_article_quota_limit CHECK(body_char_limit>0),CONSTRAINT ck_article_quota_lock CHECK(lock_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE community_write_receipts (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT NOT NULL,
 operation VARCHAR(160) COLLATE utf8mb4_0900_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_kind VARCHAR(32) NOT NULL,result_id BIGINT NOT NULL,http_status SMALLINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_community_receipt(actor_id,operation,request_key),
 CONSTRAINT fk_community_receipt_actor FOREIGN KEY(actor_id) REFERENCES sys_users(id),
 CONSTRAINT ck_receipt_key_length CHECK(CHAR_LENGTH(request_key) BETWEEN 8 AND 128),
 CONSTRAINT ck_receipt_http_status CHECK(http_status BETWEEN 100 AND 599)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE community_rate_windows (
 user_id BIGINT NOT NULL,operation VARCHAR(40) COLLATE utf8mb4_0900_bin NOT NULL,
 window_seconds INT NOT NULL,window_start DATETIME(3) NOT NULL,attempt_count INT NOT NULL,policy_id BIGINT NOT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,operation,window_seconds,window_start),
 CONSTRAINT fk_community_rate_user FOREIGN KEY(user_id) REFERENCES sys_users(id),
 CONSTRAINT fk_community_rate_policy FOREIGN KEY(policy_id) REFERENCES community_text_policies(id),
 CONSTRAINT ck_community_rate_seconds CHECK(window_seconds>0),CONSTRAINT ck_community_rate_attempts CHECK(attempt_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

UPDATE community_post_profiles p JOIN content_resources r ON r.id=p.resource_id
 LEFT JOIN content_revisions v ON v.resource_id=r.id AND v.id=r.published_revision_id
 SET p.public_edited_at=COALESCE(v.approved_at,r.published_at),p.updated_at=p.updated_at;
UPDATE community_post_profiles p LEFT JOIN (
 SELECT c.resource_id,MAX(c.created_at) last_public_comment_at FROM community_comments c
 JOIN community_comment_revisions v ON v.comment_id=c.id AND v.id=c.published_revision_id
 JOIN sys_users u ON u.id=c.user_id
 WHERE c.status='VISIBLE' AND u.status='ACTIVE' AND v.revision_state='APPROVED' GROUP BY c.resource_id
) latest ON latest.resource_id=p.resource_id
 SET p.last_public_comment_at=latest.last_public_comment_at,p.updated_at=p.updated_at;
