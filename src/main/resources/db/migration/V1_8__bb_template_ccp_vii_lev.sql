-- BB template registry: CCP VII Lev M & M
-- Class C — Simplified Callable Capital, no advance-rate columns, no ratings.
-- Feeder-vehicle group-header rows (structural, not credit tiers).
-- Multi-tab (one tab per borrower), header row 7. V1_2 seeded (CCP VII Lev M & M, A);
-- this is Class C — no conflict, but ON CONFLICT guards against repeated runs.
-- Reference: pe-sub-docs/WORKBOOK_CCP_VII_LEV.md

INSERT INTO bb_templates (template_name, template_class, sheet_name, header_row_index,
    auto_learned, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES ('CCP VII Lev M & M / Silicon Valley Bank', 'C', 'Investor List', 6, FALSE, 1, TRUE, FALSE, 0)
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
    WHERE template_name = 'CCP VII Lev M & M / Silicon Valley Bank' AND template_class = 'C'
)
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, 'Investor List', 6, 1 FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_role DO UPDATE SET
    sheet_name = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span = EXCLUDED.header_row_span;

-- Feeder-vehicle group sections (structural groupings; eligibility from per-row Excluded column)
WITH tab AS (
    SELECT bt.id AS tab_id
    FROM bb_template_tabs bt
    JOIN bb_templates tmpl ON tmpl.id = bt.template_id
    WHERE tmpl.template_name = 'CCP VII Lev M & M / Silicon Valley Bank'
      AND tmpl.template_class = 'C'
      AND bt.tab_role = 'LP_GRID'
)
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tab.tab_id, gs.group_sort, gs.header_text, gs.classification
FROM tab, (VALUES
    (1, 'Levered (Delaware) Feeder', 'Levered (Delaware) Feeder'),
    (2, '(Cayman) Feeder, L.P.',     '(Cayman) Feeder, L.P.'),
    (3, '(Delaware) Feeder, L.P.',   '(Delaware) Feeder, L.P.'),
    (4, 'Lux Intermediate',          'Lux Intermediate'),
    (5, 'Lux Non-Treaty Feeder',     'Lux Non-Treaty Feeder')
) AS gs(group_sort, header_text, classification)
ON CONFLICT ON CONSTRAINT uq_template_group_header DO UPDATE SET
    group_sort = EXCLUDED.group_sort,
    classification = EXCLUDED.classification;
