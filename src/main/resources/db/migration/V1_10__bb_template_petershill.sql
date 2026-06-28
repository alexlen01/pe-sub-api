-- Wells Fargo (Petershill IV) — Class A
-- Single-tab BB schedule with 5 LP category group sections and colour flags.
-- Note: group 2 header text contains agent-generated typo "Inlcuded" — stored verbatim.

INSERT INTO bb_templates (template_name, template_class, sheet_name, header_row_index,
    auto_learned, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES ('Wells Fargo (Petershill IV)', 'A', 'Borrowing Base', 10, FALSE, 1, TRUE, TRUE, 9)
ON CONFLICT (LOWER(template_name), template_class) DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    tranche_count = EXCLUDED.tranche_count,
    has_grouping_rows = EXCLUDED.has_grouping_rows,
    has_color_flags = EXCLUDED.has_color_flags,
    summary_rows_above_header = EXCLUDED.summary_rows_above_header;

WITH t AS (
    SELECT id FROM bb_templates
    WHERE template_name = 'Wells Fargo (Petershill IV)' AND template_class = 'A'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, 'Borrowing Base', 10, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_role DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span = EXCLUDED.header_row_span;

WITH tab AS (
    SELECT bt.id AS tab_id
    FROM bb_template_tabs bt
    JOIN bb_templates tmpl ON tmpl.id = bt.template_id
    WHERE tmpl.template_name = 'Wells Fargo (Petershill IV)'
      AND tmpl.template_class = 'A'
      AND bt.tab_role = 'LP_GRID'
)
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tab.tab_id, gs.group_sort, gs.header_text, gs.classification
FROM tab, (VALUES
    (1, 'Included Investors (Rated)',           'Rated Included'),
    (2, 'Inlcuded Investors (Non-Rated)',         'Non-Rated Included'),
    (3, 'Institutional Designated Investors',    'Designated Institutional'),
    (4, 'PWM Designated Investors',              'Designated PWM'),
    (5, 'Excluded Investors',                    'Ineligible Investors')
) AS gs(group_sort, header_text, classification)
ON CONFLICT ON CONSTRAINT uq_template_group_header DO UPDATE SET
    group_sort = EXCLUDED.group_sort,
    classification = EXCLUDED.classification;
