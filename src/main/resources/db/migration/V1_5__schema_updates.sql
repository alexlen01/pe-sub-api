-- ── Facilities: UBS account metadata ──────────────────────────────────────────
-- account_number: UBS internal identifier, format 5Vxxxxx.
-- loan_amount / maturity_date: from the credit agreement on file.
-- bank_status / bank_status_date: agent-reported facility status from Agent Bank Summary.
-- All columns nullable; populated by V1_7 facility seed migration.

ALTER TABLE facilities
    ADD COLUMN account_number   VARCHAR(20),
    ADD COLUMN loan_amount      NUMERIC(15,2),
    ADD COLUMN maturity_date    DATE,
    ADD COLUMN bank_status      VARCHAR(50),
    ADD COLUMN bank_status_date DATE;

-- ── LP Records: recallable distribution dollar value ─────────────────────────
-- `rcl` (boolean) marks whether an LP's distributions are recallable.
-- `recallable_dist` stores the actual dollar amount, used by the SVB Remaining
-- Callable Capital formula: callable_cap = uc + recallable_dist.

ALTER TABLE lp_records
    ADD COLUMN recallable_dist VARCHAR(50);

-- ── BB Templates: multi-format per-agent template class ──────────────────────
-- An agent bank may submit more than one structurally distinct template across its
-- facilities (e.g. Wells Fargo uses Class A for some funds and Class B for others).
-- `template_class` encodes the structural variant so the unique key becomes
-- (agent_bank, template_class) rather than agent_bank alone.
--
-- Class A — Full BB Schedule, group-header classification, numerical ratings,
--            Tranche A / Tranche B summary; colour-coded RCL / transferee rows.
-- Class B — Full BB Schedule, per-row "Investor Category" column, single summary table.
-- Class C — Simplified Callable Capital; no ratings or advance-rate columns;
--            binary included / excluded logic only (SVB / First Citizens format).

-- Step 1: add nullable so we can set values before enforcing NOT NULL.
ALTER TABLE bb_templates
    ADD COLUMN template_class VARCHAR(10);

-- Step 2: classify existing seed rows.
UPDATE bb_templates SET template_class = 'B' WHERE agent_bank = 'Wells Fargo';
UPDATE bb_templates SET template_class = 'C' WHERE agent_bank = 'Silicon Valley Bank';

-- Step 3: drop the single-column unique index before renaming the GS row —
-- renaming it to Wells Fargo would otherwise collide with the existing WF row.
DROP INDEX IF EXISTS idx_bb_templates_agent_bank;

-- Step 4: rename Goldman Sachs Bank USA → Wells Fargo Class A.
-- The Class A template was submitted under GS letterhead when GS was the prior
-- administrative agent for Blue Owl GP Stakes V; Wells Fargo is the current agent.
-- Structural flags for Class A: 2 tranches, group-header rows, colour-coded LP rows.
UPDATE bb_templates
SET    agent_bank         = 'Wells Fargo',
       template_class      = 'A',
       tranche_count       = 2,
       has_grouping_rows   = TRUE,
       has_color_flags     = TRUE,
       summary_rows_above_header = 0
WHERE  agent_bank = 'Goldman Sachs Bank USA';

-- Step 5: enforce NOT NULL; default 'A' for any future auto-learned entries.
ALTER TABLE bb_templates
    ALTER COLUMN template_class SET NOT NULL,
    ALTER COLUMN template_class SET DEFAULT 'A';

-- Step 6: composite unique index replaces the dropped single-column index.
CREATE UNIQUE INDEX idx_bb_templates_bank_class
    ON bb_templates (LOWER(agent_bank), template_class);
