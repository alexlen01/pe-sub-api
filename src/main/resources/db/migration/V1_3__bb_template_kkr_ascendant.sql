-- BB template registry: KKR Ascendant Fund
-- Class A — Full BB Schedule, group-header classification, single tab "Borrowing Base", header row 10.
-- ON CONFLICT DO UPDATE overwrites the placeholder row seeded by V1_2 with analyzed values.
-- Reference: pe-sub-docs/WORKBOOK_KKR_ASCENDANT.md

INSERT INTO bb_templates (template_name, template_class, sheet_name, header_row_index,
    auto_learned, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES ('JP Morgan (KKR Ascendant Fund)', 'A', 'Borrowing Base', 9, FALSE, 1, TRUE, FALSE, 9)
ON CONFLICT (LOWER(template_name), template_class) DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    tranche_count = EXCLUDED.tranche_count,
    has_grouping_rows = EXCLUDED.has_grouping_rows,
    has_color_flags = EXCLUDED.has_color_flags,
    summary_rows_above_header = EXCLUDED.summary_rows_above_header;

-- LP_GRID tab (header_row_index is 0-based: Excel row 10 → index 9)
WITH t AS (
    SELECT id FROM bb_templates
    WHERE template_name = 'JP Morgan (KKR Ascendant Fund)' AND template_class = 'A'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, 'Borrowing Base', 9, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_role DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span = EXCLUDED.header_row_span;

-- LP category group sections
WITH tab AS (
    SELECT bt.id AS tab_id
    FROM bb_template_tabs bt
    JOIN bb_templates tmpl ON tmpl.id = bt.template_id
    WHERE tmpl.template_name = 'JP Morgan (KKR Ascendant Fund)'
      AND tmpl.template_class = 'A'
      AND bt.tab_role = 'LP_GRID'
)
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tab.tab_id, gs.group_sort, gs.header_text, gs.classification
FROM tab, (VALUES
    (1, 'Rated Included Investors',     'Rated Included'),
    (2, 'Non-Rated Included Investors', 'Non-Rated Included'),
    (3, 'Designated Investors',         'Designated Institutional'),
    (4, 'Borrowing Base Investors',     'Non-Rated Included'),
    (5, 'Hurdle Investors',             'Non-Rated Included'),
    (6, 'Excluded Investors',           'Ineligible Investors')
) AS gs(group_sort, header_text, classification)
ON CONFLICT ON CONSTRAINT uq_template_group_header DO UPDATE SET
    group_sort = EXCLUDED.group_sort,
    classification = EXCLUDED.classification;
