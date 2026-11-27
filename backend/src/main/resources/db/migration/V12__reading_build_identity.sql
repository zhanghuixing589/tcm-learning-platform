-- A partial edition can be followed by a new immutable build of the same text batch.
-- The task, not the text batch or wire schema, identifies an idempotent build.
ALTER TABLE source_reading_editions
  DROP INDEX uk_reading_edition_build,
  ADD COLUMN build_job_id BIGINT NULL,
  ADD UNIQUE KEY uk_reading_build_job(build_job_id),
  ADD CONSTRAINT fk_reading_build_job FOREIGN KEY(build_job_id) REFERENCES async_jobs(id) ON DELETE RESTRICT;
