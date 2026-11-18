CREATE TABLE user_follows (
  id BIGINT NOT NULL AUTO_INCREMENT,
  follower_id BIGINT NOT NULL,
  followed_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_follows (follower_id, followed_id),
  KEY ix_user_following_time (follower_id, created_at, id),
  KEY ix_user_followers_time (followed_id, created_at, id),
  CONSTRAINT ck_user_follows_not_self CHECK (follower_id <> followed_id),
  CONSTRAINT fk_user_follows_follower FOREIGN KEY (follower_id) REFERENCES sys_users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_user_follows_followed FOREIGN KEY (followed_id) REFERENCES sys_users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有向用户关注关系';
