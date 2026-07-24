-- Persist the structured XLSX workbook used to import each BB template. The
-- registry row keeps only lightweight metadata so list queries do not load
-- workbook bytes; the file row is deleted automatically with its template.
ALTER TABLE bb_templates
    ADD COLUMN source_file_name VARCHAR(255),
    ADD COLUMN source_file_size BIGINT;

CREATE TABLE bb_template_files (
    template_id  INTEGER PRIMARY KEY REFERENCES bb_templates(id) ON DELETE CASCADE,
    content_type VARCHAR(255) NOT NULL,
    content      BYTEA        NOT NULL
);
