-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  BB Template: Petershill IV                                            ║
-- ║  Class A — group-header classification, single tab "Borrowing Base".   ║
-- ║  Agent: Goldman Sachs Bank USA                                          ║
-- ║  Source: Agent-BB-Petershill-IV.xlsx (ground-truth parse)              ║
-- ╚══════════════════════════════════════════════════════════════════════════╝

-- ── Structure confirmed from Excel ───────────────────────────────────────────
--   R2  : "Petershill IV"                               ← title anchor
--   R3  : "Agent Bank | Goldman Sachs Bank USA"
--   R11 : column header row (15 columns, 1-based → index 10 zero-based)
--   Group sections follow header row

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
    'petershill-iv',
    'Petershill IV',
    'Goldman Sachs Bank USA',
    'A',
    'Borrowing Base',
    10,                 -- 0-based index; Excel row 11
    FALSE,
    1,
    TRUE,
    FALSE,
    FALSE,
    '2-10',
    2,
    'Petershill IV',
    '["petershill iv", "petershill", "goldman sachs bank usa"]',
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

WITH t AS (
    SELECT id FROM bb_templates WHERE template_slug = 'petershill-iv'
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
    10,
    1,
    '["Investor","Moody''s","S&P","Fitch","Net Worth","Total Commitment","Funded Commitment","Unfunded Commitment","% Total Unfunded Commitment","Concentration Limit","Excess Concentration","Eligible Unfunded Commitment","Advance Rate","UBS Borrowing Base","Agent Borrowing Base"]'
FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_sort DO UPDATE SET
    sheet_name       = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span  = EXCLUDED.header_row_span,
    columns          = EXCLUDED.columns;

-- ── LP category group sections ────────────────────────────────────────────────

WITH tab AS (
    SELECT bt.id AS tab_id
    FROM bb_template_tabs bt
    JOIN bb_templates tmpl ON tmpl.id = bt.template_id
    WHERE tmpl.template_slug = 'petershill-iv'
      AND bt.tab_role = 'LP_GRID'
)
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tab.tab_id, gs.group_sort, gs.header_text, gs.classification
FROM tab, (VALUES
    (1, 'Included Investors (Rated)',        'Rated Included'),
    (2, 'Included Investors (Non-Rated)',    'Non-Rated Included'),
    (3, 'Institutional Designated Investors','Designated Institutional'),
    (4, 'PWM Designated Investors',          'Designated PWM'),
    (5, 'Excluded Investors',                'Ineligible Investors')
) AS gs(group_sort, header_text, classification)
ON CONFLICT ON CONSTRAINT uq_template_group_header DO UPDATE SET
    group_sort     = EXCLUDED.group_sort,
    classification = EXCLUDED.classification;
