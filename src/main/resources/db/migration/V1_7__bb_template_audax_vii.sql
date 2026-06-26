-- BB template registry: Audax Fund VII
-- Class B — per-row "Included/Excluded Investor" column, multi-tab (one tab per borrower),
-- header row 13, no group sections. V1_2 seeded (Audax Fund VII, A); this is Class B — no conflict,
-- but ON CONFLICT guards against repeated runs.
-- Reference: pe-sub-docs/WORKBOOK_AUDAX_VII.md

INSERT INTO bb_templates (template_name, template_class, sheet_name, header_row_index,
    auto_learned, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES ('Audax Fund VII / Silicon Valley Bank', 'B', 'Investor List', 12, FALSE, 1, FALSE, FALSE, 0)
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
    WHERE template_name = 'Audax Fund VII / Silicon Valley Bank' AND template_class = 'B'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, 'Investor List', 12, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_role DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span = EXCLUDED.header_row_span;

-- No group sections for Class B (per-row "Included/Excluded Investor" column drives eligibility).
