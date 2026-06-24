-- BB template registry: AEP VII (Aurora Equity Partners VII)
-- Class A — Full BB Schedule, group-header classification, cell-format legend (LP delta flags).
-- Single tab "BB", header row 11, colour-coded rows.
-- ON CONFLICT DO UPDATE overwrites the placeholder row seeded by V1_2 with analyzed values.
-- Reference: pe-sub-docs/WORKBOOK_AEP_VII.md

INSERT INTO bb_templates (agent_bank, template_class, sheet_name, header_row_index,
    auto_learned, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES ('AEP VII / JP Morgan', 'A', 'BB', 10, FALSE, 1, TRUE, TRUE, 9)
ON CONFLICT (LOWER(agent_bank), template_class) DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    tranche_count = EXCLUDED.tranche_count,
    has_grouping_rows = EXCLUDED.has_grouping_rows,
    has_color_flags = EXCLUDED.has_color_flags,
    summary_rows_above_header = EXCLUDED.summary_rows_above_header;

-- LP_GRID tab
WITH t AS (
    SELECT id FROM bb_templates
    WHERE agent_bank = 'AEP VII / JP Morgan' AND template_class = 'A'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, 'BB', 10, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_role DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span = EXCLUDED.header_row_span;

-- LP category group sections
WITH tab AS (
    SELECT bt.id AS tab_id
    FROM bb_template_tabs bt
    JOIN bb_templates tmpl ON tmpl.id = bt.template_id
    WHERE tmpl.agent_bank = 'AEP VII / JP Morgan'
      AND tmpl.template_class = 'A'
      AND bt.tab_role = 'LP_GRID'
)
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tab.tab_id, gs.group_sort, gs.header_text, gs.classification
FROM tab, (VALUES
    (1, 'Rated Included Investors',     'Rated Included'),
    (2, 'Non-Rated Included Investors', 'Non-Rated Included'),
    (3, 'Designated Investors',         'Designated Institutional'),
    (4, 'Excluded Investors',           'Ineligible Investors')
) AS gs(group_sort, header_text, classification)
ON CONFLICT ON CONSTRAINT uq_template_group_header DO UPDATE SET
    group_sort = EXCLUDED.group_sort,
    classification = EXCLUDED.classification;
