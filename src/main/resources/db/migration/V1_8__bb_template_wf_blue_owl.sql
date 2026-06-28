-- BB template registry: Blue Owl GP Stakes V — Wells Fargo (current agent)
-- Class A — Full BB Schedule, two tranches (Tranche A + B, each an "Agent BB" tab),
-- group-header classification, cell-format legend (reclassification / transfer flags).
-- Reference: pe-sub-docs/WORKBOOK_WF_BLUE_OWL.md

INSERT INTO bb_templates (template_name, template_class, sheet_name, header_row_index,
    auto_learned, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES ('Wells Fargo (Blue Owl GP Stakes V)', 'A', 'Agent BB', 17, FALSE, 2, TRUE, TRUE, 15)
ON CONFLICT (LOWER(template_name), template_class) DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    tranche_count = EXCLUDED.tranche_count,
    has_grouping_rows = EXCLUDED.has_grouping_rows,
    has_color_flags = EXCLUDED.has_color_flags,
    summary_rows_above_header = EXCLUDED.summary_rows_above_header;

-- LP_GRID tab (the unique constraint on (template_id, tab_role) allows only one LP_GRID row;
-- the engine uses tranche_count=2 to process both Agent BB tabs in turn).
WITH t AS (
    SELECT id FROM bb_templates
    WHERE template_name = 'Wells Fargo (Blue Owl GP Stakes V)' AND template_class = 'A'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, 'Agent BB', 17, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_role DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span = EXCLUDED.header_row_span;

-- LP category group sections
WITH tab AS (
    SELECT bt.id AS tab_id
    FROM bb_template_tabs bt
    JOIN bb_templates tmpl ON tmpl.id = bt.template_id
    WHERE tmpl.template_name = 'Wells Fargo (Blue Owl GP Stakes V)'
      AND tmpl.template_class = 'A'
      AND bt.tab_role = 'LP_GRID'
)
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tab.tab_id, gs.group_sort, gs.header_text, gs.classification
FROM tab, (VALUES
    (1, 'A. Rated Investors',    'Rated Included'),
    (2, 'B. Unrated Investors',  'Non-Rated Included'),
    (3, 'C. Eligible Investors', 'Designated Institutional'),
    (4, 'D. Excluded Investors', 'Excluded')
) AS gs(group_sort, header_text, classification)
ON CONFLICT ON CONSTRAINT uq_template_group_header DO UPDATE SET
    group_sort = EXCLUDED.group_sort,
    classification = EXCLUDED.classification;
