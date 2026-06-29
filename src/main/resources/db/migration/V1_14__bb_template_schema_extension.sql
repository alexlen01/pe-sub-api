-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  BB Template Schema Extension                                          ║
-- ║  Restores multi-tab columns deleted with V1_11, then adds all          ║
-- ║  recognition + display fields matching the pe-sub-platform prototype   ║
-- ║  TemplateProfile model. Reference: BB_TEMPLATE_CHANGES_JUNE_28.md.     ║
-- ╚══════════════════════════════════════════════════════════════════════════╝

-- ── Restore multi-tab support (was V1_11, now deleted) ────────────────────────

-- Allow multiple LP_GRID rows per template (one per sleeve / borrower tab).
-- Old constraint limited each template to exactly one row per tab_role.
ALTER TABLE bb_template_tabs
    ADD COLUMN IF NOT EXISTS sleeve_name VARCHAR(255);

ALTER TABLE bb_template_tabs
    DROP CONSTRAINT IF EXISTS uq_template_tab_role;

ALTER TABLE bb_template_tabs
    ADD CONSTRAINT uq_template_tab_sort UNIQUE (template_id, tab_sort);

-- Auto-discover mode: when TRUE the engine scans every workbook sheet instead
-- of matching by named sheet_name (used for CCP VII where borrower tab names vary).
ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS auto_discover_tabs BOOLEAN NOT NULL DEFAULT FALSE;

-- Fund sleeve provenance carried per LP record from multi-tab extractions.
ALTER TABLE lp_records
    ADD COLUMN IF NOT EXISTS fund_sleeve VARCHAR(255);

-- ── Recognition fields ────────────────────────────────────────────────────────

-- Stable kebab-case identifier (e.g. 'kkr-ascendant') used by the extraction
-- engine and UI instead of the numeric surrogate key.
ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS template_slug VARCHAR(50);

CREATE UNIQUE INDEX IF NOT EXISTS idx_bb_templates_slug
    ON bb_templates (template_slug);

-- Human-readable agent bank name, separate from the fund name stored in
-- template_name. Drives the "Agent / Fund" column in the BBTemplates registry.
ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS agent_name VARCHAR(255);

-- Excel row (1-based) and exact cell text of the fund-level title anchor.
-- The extraction engine reads this cell first to confirm template identity
-- before parsing column headers.
ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS title_row  INTEGER,
    ADD COLUMN IF NOT EXISTS title_text TEXT;

-- Summary block row range (e.g. '2-9') — rows skipped before the header row
-- that contain as-of date, currency, totals, etc. Replaces the integer
-- summary_rows_above_header which could not express non-contiguous ranges.
ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS summary_row_range VARCHAR(20);

-- Additional keyword signals for filename / facility / fund matching in the
-- frontend detectTemplate() heuristic and backend TemplateDetector.
ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS detect_keys JSONB NOT NULL DEFAULT '[]';

-- ── Display / registry fields ─────────────────────────────────────────────────

-- Cell-format legend entries [{style, meaning}] — e.g. AEP VII green/yellow
-- shading encoding LP deltas. Rendered in the BBTemplates detail panel.
ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS legend JSONB NOT NULL DEFAULT '[]';

-- Free-text analyst notes about extraction quirks for this template.
ALTER TABLE bb_templates
    ADD COLUMN IF NOT EXISTS notes JSONB NOT NULL DEFAULT '[]';

-- Ordered list of column header strings exactly as they appear in the workbook.
-- Drives the "Columns Extracted" list in the BBTemplates detail panel and
-- provides the canonical order for alias resolution.
ALTER TABLE bb_template_tabs
    ADD COLUMN IF NOT EXISTS columns JSONB NOT NULL DEFAULT '[]';
