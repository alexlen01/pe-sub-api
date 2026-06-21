-- Operator-forced Agent BB template for a submission. When the Document Recognition
-- auto-detection picks the wrong fund template (e.g. an Audax Fund VII workbook recognised
-- as KKR Ascendant), the operator picks the correct format from the dropdown; that choice is
-- persisted here so every subsequent re-extraction (remap, discard) re-applies the same forced
-- template instead of reverting to auto-detection. Null = auto-detect (the default).
ALTER TABLE submission_extractions ADD COLUMN forced_template VARCHAR(120);
