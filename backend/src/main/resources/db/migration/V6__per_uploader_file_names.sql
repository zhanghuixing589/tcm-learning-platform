-- Identical bytes may be uploaded by unrelated users. Keep each uploader's filename private.
ALTER TABLE file_upload_grants ADD COLUMN original_name VARCHAR(255) NULL AFTER user_id;
UPDATE file_upload_grants g JOIN file_objects f ON f.id=g.file_id
 SET g.original_name=CASE WHEN g.user_id=f.uploaded_by THEN f.original_name
 ELSE CONCAT('source',CASE f.mime_type WHEN 'text/plain' THEN '.txt'
 WHEN 'application/pdf' THEN '.pdf' ELSE '.docx' END) END;
