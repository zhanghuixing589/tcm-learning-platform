-- Frozen submission rights and current effective rights have different lifetimes.
-- Do not invent a historical approval snapshot for pre-existing rows.
ALTER TABLE source_documents ADD COLUMN reviewed_rights JSON NULL;
