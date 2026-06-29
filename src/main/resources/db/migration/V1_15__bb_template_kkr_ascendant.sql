-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  BB Template: KKR Ascendant Fund                                       ║
-- ║  Class A — group-header classification, single tab "Borrowing Base".   ║
-- ║  Agent: KKR Capital Markets                                             ║
-- ║  Source: Agent-BB-KKR-Ascendant-Fund.xlsx (ground-truth parse)         ║
-- ║  Reference: WORKBOOK_KKR_ASCENDANT.md · BB_TEMPLATE_CHANGES_JUNE_28.md ║
-- ╚══════════════════════════════════════════════════════════════════════════╝

-- ── Structure confirmed from Excel ───────────────────────────────────────────
--   R2  : "KKR Ascendant – Borrowing Base"          ← title anchor
--   R3  : "Agent Bank | KKR Capital Markets"         ← agent bank
--   R4  : "Facility | KKR Ascendant Fund"
--   R5–8: summary rows (Total Investors, Unfunded, Eligibility Basis, Prepared By)
--   R9  : blank
--   R10 : column header row (13 columns, 1-based → index 9 zero-based)
--   R11 : "Rated Included Investors"                ← first group header
--   R21 : "Non-Rated Included Investors"
--   R31 : "Designated Investors"
--   R41 : "Borrowing Base Investors"
--   R51 : "Hurdle Investors"
--   R61 : "Excluded Investors"

INSERT INTO bb_templates (
    template_slug,
    template_name,
    agent_name,
    template_class,
    sheet_name,
    header_row_index,
    auto_learned,
    tranche_count,
    has_grouping_rows,
    has_color_flags,
    auto_discover_tabs,
    summary_row_range,
    title_row,
    title_text,
    detect_keys,
    legend,
    notes
)
VALUES (
    'kkr-ascendant',
    'KKR Ascendant Fund',
    'KKR Capital Markets',
    'A',
    'Borrowing Base',
    9,                  -- 0-based index; Excel row 10
    FALSE,
    1,
    TRUE,
    FALSE,
    FALSE,
    '2-9',             -- rows 2-9 are summary block (skipped before header)
    2,
    'KKR Ascendant – Borrowing Base',
    '["kkr ascendant", "kkr capital markets", "kkr ascendant fund"]',
    '[]',
    '[]'
)
ON CONFLICT (template_slug) DO UPDATE SET
    template_name      = EXCLUDED.template_name,
    agent_name         = EXCLUDED.agent_name,
    template_class     = EXCLUDED.template_class,
    sheet_name         = EXCLUDED.sheet_name,
    header_row_index   = EXCLUDED.header_row_index,
    auto_learned       = EXCLUDED.auto_learned,
    tranche_count      = EXCLUDED.tranche_count,
    has_grouping_rows  = EXCLUDED.has_grouping_rows,
    has_color_flags    = EXCLUDED.has_color_flags,
    auto_discover_tabs = EXCLUDED.auto_discover_tabs,
    summary_row_range  = EXCLUDED.summary_row_range,
    title_row          = EXCLUDED.title_row,
    title_text         = EXCLUDED.title_text,
    detect_keys        = EXCLUDED.detect_keys,
    legend             = EXCLUDED.legend,
    notes              = EXCLUDED.notes,
    updated_at         = NOW();

-- ── LP_GRID tab ───────────────────────────────────────────────────────────────
-- 13 columns exactly as they appear in the workbook header row (R10).
-- skip_row_keywords default covers Total/Subtotal; group-header rows are
-- handled separately via bb_template_groups (not skipped, but consumed as
-- LP-category context rows by the extraction engine).

WITH t AS (
    SELECT id FROM bb_templates WHERE template_slug = 'kkr-ascendant'
)
INSERT INTO bb_template_tabs (
    template_id,
    tab_role,
    tab_sort,
    sheet_name,
    header_row_index,
    header_row_span,
    columns
)
SELECT
    t.id,
    'LP_GRID',
    1,
    'Borrowing Base',
    9,
    1,
    '["Investor","Fund Sleeve","Moody''s","S&P","Net Worth","Total Commitment","Funded Commitment","Unfunded Commitment","% Total Unfunded Commitment","Concentration Limit","Eligible Unfunded Commitment","Advance Rate","Borrowing Base"]'
FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_sort DO UPDATE SET
    sheet_name       = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span  = EXCLUDED.header_row_span,
    columns          = EXCLUDED.columns;

-- ── LP category group sections ────────────────────────────────────────────────
-- Six group-header rows appear in the workbook, each setting the LP category
-- for all data rows beneath it until the next group header or end of sheet.
--
-- "Borrowing Base Investors" and "Hurdle Investors" are non-rated LPs that
-- pass concentration and hurdle tests; both map to Non-Rated Included per the
-- credit agreement advance-rate schedule.

WITH tab AS (
    SELECT bt.id AS tab_id
    FROM bb_template_tabs bt
    JOIN bb_templates tmpl ON tmpl.id = bt.template_id
    WHERE tmpl.template_slug = 'kkr-ascendant'
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
    group_sort     = EXCLUDED.group_sort,
    classification = EXCLUDED.classification;
