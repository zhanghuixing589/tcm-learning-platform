-- Absolute session lifetime is fixed at the first login, never extended by rotation.
ALTER TABLE auth_refresh_tokens
    ADD COLUMN absolute_expires_at DATETIME(3) NULL COMMENT '登录会话绝对到期UTC，轮换时继承' AFTER expires_at;

-- Materialize before updating to avoid reading the updated table through a merged derived table.
CREATE TEMPORARY TABLE session_deadlines AS
    SELECT user_id, family_key, MIN(created_at) + INTERVAL 30 DAY AS deadline
    FROM auth_refresh_tokens GROUP BY user_id, family_key;
UPDATE auth_refresh_tokens t
    JOIN session_deadlines s ON s.user_id=t.user_id AND s.family_key=t.family_key
    SET t.absolute_expires_at=s.deadline,
        t.expires_at=LEAST(t.expires_at,s.deadline),
        t.revoked_at=IF(s.deadline<=UTC_TIMESTAMP(3),COALESCE(t.revoked_at,UTC_TIMESTAMP(3)),t.revoked_at);
DROP TEMPORARY TABLE session_deadlines;

ALTER TABLE auth_refresh_tokens
    MODIFY COLUMN absolute_expires_at DATETIME(3) NOT NULL COMMENT '登录会话绝对到期UTC，轮换时继承',
    ADD CONSTRAINT ck_refresh_absolute_expiry CHECK (expires_at <= absolute_expires_at);
