-- BB template registry: CP VII (Carlyle Partners VII)
-- Class A — Full BB Schedule, deep-sheet title (row 83), stacked two-row header (rows 84-85).
-- No LP category group sections (flat LP list). Multi-tab.
-- ON CONFLICT DO UPDATE overwrites the placeholder row seeded by V1_2 with analyzed values.
-- Reference: pe-sub-docs/WORKBOOK_CP_VII.md

INSERT INTO bb_templates (template_name, template_class, sheet_name, header_row_index,
    auto_learned, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES ('Silicon Valley Bank (CP VII)', 'A', 'BB', 83, FALSE, 1, FALSE, FALSE, 0)
ON CONFLICT (LOWER(template_name), template_class) DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    tranche_count = EXCLUDED.tranche_count,
    has_grouping_rows = EXCLUDED.has_grouping_rows,
    has_color_flags = EXCLUDED.has_color_flags,
    summary_rows_above_header = EXCLUDED.summary_rows_above_header;

-- LP_GRID tab — header_row_span = 2 because column headers span rows 84 and 85.
WITH t AS (
    SELECT id FROM bb_templates
    WHERE template_name = 'Silicon Valley Bank (CP VII)' AND template_class = 'A'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, 'BB', 83, 2 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_role DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span = EXCLUDED.header_row_span;

-- No group sections for this template (flat LP list after stacked header).
