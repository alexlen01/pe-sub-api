-- Natural (source-file) ordering for LP records.
-- source_seq captures each LP's position in the originating Agent BB (the extraction row
-- index) so the LP Master can be returned in the order the records appeared in the uploaded
-- file rather than alphabetically. Nullable: legacy rows and manually-created LPs have no
-- source position, so they sort last (PostgreSQL ASC orders NULLs last by default).
ALTER TABLE lp_records ADD COLUMN source_seq INTEGER;
