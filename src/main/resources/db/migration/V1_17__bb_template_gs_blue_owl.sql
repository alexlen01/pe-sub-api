-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  BB Template: Blue Owl GP Stakes V (Goldman Sachs format)              ║
-- ║  FLAT list — zero group-header rows.                                   ║
-- ║  Agent: Goldman Sachs Bank USA                                          ║
-- ║  Source: Agent-BB-Blue-Owl-GP-Stakes-V.xlsx (ground-truth parse)       ║
-- ╚══════════════════════════════════════════════════════════════════════════╝

-- ── Structure confirmed from Excel ───────────────────────────────────────────
--   R1  : "Blue Owl GP Stakes V — Agent Borrowing Base Certificate"  ← title
--   R3-6: Summary block (Facility, As Of Date, Currency, blank)
--   R7  : Column header row (13 columns, 0-based index 6)
--   R8+ : Flat LP list (~900 rows, NO section-banner rows)

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
    'gs-blue-owl',
    'Blue Owl GP Stakes V',
    'Goldman Sachs Bank USA',
    'A',
    'Borrowing Base',
    6,                  -- 0-based index; Excel row 7
    FALSE,
    1,
    FALSE,              -- flat list, no LP-category group sections
    FALSE,
    FALSE,
    '1-6',
    1,
    'Blue Owl GP Stakes V — Agent Borrowing Base Certificate',
    '["blue owl gp stakes v", "blue owl gp stakes", "goldman sachs bank usa", "gp stakes v"]',
    '[]',
    '["Flat LP schedule (~900 LPs). No section-banner rows. LP category is a column value (Investor Type), not a group header."]'
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
    SELECT id FROM bb_templates WHERE template_slug = 'gs-blue-owl'
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
    6,
    1,
    '["Investor Name (Agent Records)","Investor Type","Commitment (USD)","Uncalled Capital (USD)","AUM","S&P","Moody''s","Fitch","Advance Rate","Borrowing Base Contribution","Concentration Limit","% Called","% of Borrowing Base"]'
FROM t
ON CONFLICT ON CONSTRAINT uq_template_tab_sort DO UPDATE SET
    sheet_name       = EXCLUDED.sheet_name,
    header_row_index = EXCLUDED.header_row_index,
    header_row_span  = EXCLUDED.header_row_span,
    columns          = EXCLUDED.columns;

-- No bb_template_groups rows — this template uses a flat LP list.
