-- BB template registry: Goldman Sachs (Blue Owl GP Stakes V)
-- Class B — per-row LP Classification column, flat LP list (~900 LPs), single tab "Borrowing Base",
-- header row 7, no group sections.
-- Reference: pe-sub-docs/WORKBOOK_GS_BLUE_OWL.md

INSERT INTO bb_templates (template_name, template_class, sheet_name, header_row_index,
    auto_learned, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES ('Goldman Sachs (Blue Owl GP Stakes V)', 'B', 'Borrowing Base', 6, FALSE, 1, FALSE, FALSE, 6)
ON CONFLICT (LOWER(template_name), template_class) DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    tranche_count = EXCLUDED.tranche_count,
    has_grouping_rows = EXCLUDED.has_grouping_rows,
    has_color_flags = EXCLUDED.has_color_flags,
    summary_rows_above_header = EXCLUDED.summary_rows_above_header;

-- LP_GRID tab
WITH t AS (
    SELECT id FROM bb_templates
    WHERE template_name = 'Goldman Sachs (Blue Owl GP Stakes V)' AND template_class = 'B'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, 'Borrowing Base', 6, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_role DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span = EXCLUDED.header_row_span;

-- No group sections for Class B (per-row "LP Classification" column drives categorisation).
