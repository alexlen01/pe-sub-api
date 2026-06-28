-- Multi-tab BB support: allow multiple LP_GRID tabs per template (e.g. Audax Fund VII
-- with Nerdio / Apptio / Marlin sleeves; CCP VII Lev M & M with auto-discovered tabs).
--
-- Changes:
--   bb_template_tabs  — drop (template_id, tab_role) unique key; add (template_id, tab_sort)
--                       so multiple LP_GRID rows can coexist; add sleeve_name for display.
--   bb_templates      — add auto_discover_tabs flag: when TRUE the engine scans all workbook
--                       sheets instead of using a named tab (used when sleeve names vary
--                       per workbook, e.g. CCP VII Lev M & M borrowers).
--   lp_records        — add fund_sleeve VARCHAR to carry per-record sleeve provenance.

-- ── bb_template_tabs ─────────────────────────────────────────────────────────

ALTER TABLE bb_template_tabs
    ADD COLUMN IF NOT EXISTS sleeve_name VARCHAR(255);

ALTER TABLE bb_template_tabs
    DROP CONSTRAINT IF EXISTS uq_template_tab_role;

ALTER TABLE bb_template_tabs
    ADD CONSTRAINT uq_template_tab_sort UNIQUE (template_id, tab_sort);

-- ── bb_templates ─────────────────────────────────────────────────────────────

ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS auto_discover_tabs BOOLEAN NOT NULL DEFAULT FALSE;

-- ── lp_records ───────────────────────────────────────────────────────────────

ALTER TABLE lp_records
    ADD COLUMN IF NOT EXISTS fund_sleeve VARCHAR(255);
