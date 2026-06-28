-- Multi-tab template configuration for Audax Fund VII and CCP VII Lev M & M.
--
-- Silicon Valley Bank (Audax Fund VII) — Class B:
--   Three named sleeves (Nerdio, Apptio, Marlin), each a separate Excel tab.
--   The old single LP_GRID tab (sort=1, 'Investor List') is replaced by the three
--   sleeve tabs. The existing row (sort=1) is updated to Nerdio; Apptio and Marlin
--   are inserted as sort=2 and sort=3.
--
-- Silicon Valley Bank (CCP VII Lev M & M) — Class C:
--   Tab names vary per workbook upload (one tab per borrower entity). Set
--   auto_discover_tabs = TRUE so the engine scans all sheets at extraction time
--   and extracts LP records from any sheet that passes header detection.
--   The existing LP_GRID tab row (headerRowIndex=6) is kept as the header-location
--   reference that the auto-discover scan uses.

-- ── Audax Fund VII — replace single tab with three named sleeve tabs ──────────

-- Update existing sort=1 row to the Nerdio sleeve
WITH t AS (
    SELECT id FROM bb_templates
    WHERE LOWER(template_name) = LOWER('Silicon Valley Bank (Audax Fund VII)')
      AND template_class = 'B'
)
UPDATE bb_template_tabs
SET sheet_name  = 'Nerdio',
    sleeve_name = 'Nerdio',
    tab_sort    = 1
WHERE template_id = (SELECT id FROM t)
  AND tab_role    = 'LP_GRID'
  AND tab_sort    = 1;

-- Insert Apptio sleeve (sort=2)
WITH t AS (
    SELECT id FROM bb_templates
    WHERE LOWER(template_name) = LOWER('Silicon Valley Bank (Audax Fund VII)')
      AND template_class = 'B'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, sleeve_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 2, 'Apptio', 'Apptio', 12, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_sort DO UPDATE SET
    sheet_name  = EXCLUDED.sheet_name,
    sleeve_name = EXCLUDED.sleeve_name,
    header_row_index = EXCLUDED.header_row_index;

-- Insert Marlin sleeve (sort=3)
WITH t AS (
    SELECT id FROM bb_templates
    WHERE LOWER(template_name) = LOWER('Silicon Valley Bank (Audax Fund VII)')
      AND template_class = 'B'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, sleeve_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 3, 'Marlin', 'Marlin', 12, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_sort DO UPDATE SET
    sheet_name  = EXCLUDED.sheet_name,
    sleeve_name = EXCLUDED.sleeve_name,
    header_row_index = EXCLUDED.header_row_index;

-- ── CCP VII Lev M & M — enable auto-discover (tab names vary per upload) ─────

UPDATE bb_templates
SET auto_discover_tabs = TRUE
WHERE LOWER(template_name) = LOWER('Silicon Valley Bank (CCP VII Lev M & M)')
  AND template_class = 'C';
