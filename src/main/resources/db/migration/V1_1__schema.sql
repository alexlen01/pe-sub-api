-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  Consolidated schema — all DDL in final form.                          ║
-- ║  Covers original tables plus all ALTER TABLE additions, including the   ║
-- ║  V1_14 BB-template registry extension (recognition + display fields).   ║
-- ║  Seed data lives in V1_2. BB template seed rows live in V1_15–V1_22.    ║
-- ╚══════════════════════════════════════════════════════════════════════════╝

-- ── Core tables ───────────────────────────────────────────────────────────────

-- Directory of people who have authenticated, populated from the trusted gateway's X-Auth-*
-- headers on each authenticated request (UserDirectoryService). This is NOT a credential store:
-- there is no password column and the application never authenticates anyone itself — SSO does.
-- Rows exist so screens can render "who did this" for a stored uuName without calling out to a
-- corporate directory.
--
-- uu_name is the stable authentication identity (e.g. le05751) and the natural key; email and
-- surname can both change over time, so neither is the key. A real uuName is 7 alphanumeric
-- characters — the column is VARCHAR(50) purely as headroom for system/override identities passed
-- in as variables, not because any person's uuName approaches that length.
-- role holds the highest-privilege human role the gateway asserted (Manager > Analyst > Viewer);
-- machine SERVICE principals are never written here.
-- last_seen_at is refreshed on a throttle, not on literally every request.
CREATE TABLE users (
    id           SERIAL PRIMARY KEY,
    uu_name      VARCHAR(50)  NOT NULL UNIQUE,
    first_name   VARCHAR(255) NOT NULL DEFAULT '',
    last_name    VARCHAR(255) NOT NULL DEFAULT '',
    email        VARCHAR(255) NOT NULL DEFAULT '',
    role         VARCHAR(50)  NOT NULL DEFAULT 'Viewer',
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- account_number    : UBS internal identifier, format 5Vxxxxx.
-- loan_amount /
-- maturity_date     : from the credit agreement on file.
-- bank_status /
-- bank_status_date  : agent-reported facility status from Agent Bank Summary.
-- facility_size     : total facility size (credit agreement); used in Shadow BB summary.
-- ubs_participation : UBS participation dollar amount; used in Shadow BB summary.
-- collateral_date   : effective collateral / valuation date for the most recent BB run.
CREATE TABLE facilities (
    id               SERIAL PRIMARY KEY,
    name             VARCHAR(255)   NOT NULL UNIQUE,
    agent_bank       VARCHAR(255)   NOT NULL,
    status           VARCHAR(50)    NOT NULL DEFAULT 'Not Started',
    conc_limit_m     NUMERIC(10, 2) NOT NULL DEFAULT 25,
    account_number   VARCHAR(20),
    loan_amount      NUMERIC(15, 2),
    maturity_date    DATE,
    bank_status      VARCHAR(50),
    bank_status_date DATE,
    facility_size    NUMERIC(15, 2),
    ubs_participation NUMERIC(15, 2),
    collateral_date  DATE,
    last_run_at      TIMESTAMP,
    created_at       TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Column names are spelled out in full. Three pairs were previously abbreviated in ways that
-- actively misled, and must not be reintroduced:
--   * high_quality was `high_qty`, which reads as "high quantity". It is a BB quality tier flag.
--   * ubs_lp_category / agent_lp_category were `classification` / `agent_cls`. LP Category (the
--     bank's BB risk bucket) is a different thing from LP Classification (regulatory status:
--     QP/QIB/ERISA) and from investor_type (industry profile). Never collapse the three.
--   * institutional_or_hnw was `inst_vs_hnw`; it holds exactly 'Institutional' or 'HNW'.
--
-- Shadow BB 28-column alignment (Shadow_BB.xlsx · Upload Agent BB Step 5):
--   ubs_lp_category   → UBS LP Category         | agent_lp_category → Agent LP Category
--   ubs_advance_rate  → UBS Advance Rate        | agent_advance_rate → Agent Advance Rate
--   agent_excess_concentration → Agent Excess Conc Base
--   ubs_excess_concentration   → UBS Excess Conc Base
--   agent_borrowing_base → Agent Borrowing Base | ubs_borrowing_base → UBS Borrowing Base
-- The UBS advance rate is UBS's own rate for the LP and overrides the bb_criteria_matrix default;
-- the agent advance rate is the agent bank's rate, extracted verbatim from their workbook. The
-- spread between the two is what the Shadow BB exists to measure.
-- recallable_distributions : dollar value behind the `reclassified` flag.
-- source_seq      : LP's row position in the originating Agent BB (extraction row index);
--                   nullable — legacy / manually-created LPs sort last (NULL LAST).
--
-- Column types/widths for workbook-derived data (real Agent BB values must be stored verbatim,
-- never truncated; dollar amounts never rounded):
--   * free-text labels (types, categories, ranges, regions)       -> VARCHAR(255)
--   * money on lp_records (capital_commitment, called_capital,
--     uncalled_capital, agent_borrowing_base) and the
--     concentration limits                                        -> NUMERIC(20, 2)
--   * the LP-size display fields (aum, nav, pension_assets), which
--     stay text on both lp_records and lp_master                  -> VARCHAR(50)
--   * percents and advance rates (both advance rates, the three
--     pct_* columns, funding_ratio, lp_master's default rate)     -> NUMERIC(7, 4)
--   * agency ratings incl. outlook qualifiers (width is defensive
--     headroom for legacy/pass-through values)                    -> VARCHAR(50)
-- Enum-like app-controlled columns (status, decision, tab_role, agent_lp_category_source,
-- template_class) keep tighter widths.
--
-- The money that drives the borrowing base (capital_commitment, called_capital, uncalled_capital,
-- agent_borrowing_base) is one precise NUMERIC column per field, in absolute dollars, with no
-- formatted display-string sibling. The BB engine reads the numeric directly
-- (BbCalculationService.dollarM) so the borrowing base is computed from exact dollars rather than a
-- re-parsed "$12.3M"; DTOs format for display on the way out (MoneyValues.display), which is why an
-- abbreviated input such as "$4.2B" round-trips as "$4,200,000,000" and never re-abbreviates.
--
-- aum / nav / pension_assets are the exception: they are LP-size *display* fields, never BB inputs,
-- and stay VARCHAR on lp_records exactly as on lp_master, so the copy across that boundary is a
-- plain assignment. The one numeric consumer is the LP-size report aggregate, which parses on read.
--
-- Every percent/rate column is NUMERIC(7,4) holding a *fraction*, never a percent-scaled number and
-- never a formatted string: 0.9100 is 91%. This matches the lp_rates convention below, and unlike
-- money the API wire format is numeric too — DTOs emit the raw fraction and pe-sub-ui formats it for
-- display (formatPercent in utils/percent.ts). The one exception is the pair below.
--
-- agent_concentration_limit / ubs_concentration_limit are NUMERIC and hold either a percentage of
-- total uncalled capital (7.5 = 7.5%) or an absolute dollar cap (25000000 = $25M). The two are told
-- apart by magnitude at BbCalculationService.ABSOLUTE_DOLLAR_MIN (100,000) — the same threshold
-- parseMoney applies to suffix-less strings. They are therefore NOT on the fraction scale.
--
-- Multiple LP rows with the same investor_name may exist within one facility
-- (for sleeves/vintages/SPVs). Row identity is the surrogate id.
CREATE TABLE lp_master (
    id                             SERIAL        PRIMARY KEY,
    investor_name                  VARCHAR(255)  NOT NULL UNIQUE,
    parent                         VARCHAR(255),
    spv                            BOOLEAN       NOT NULL DEFAULT FALSE,
    high_quality                   BOOLEAN       NOT NULL DEFAULT TRUE,
    investor_type                  VARCHAR(255),
    institutional_or_hnw           VARCHAR(255),
    region_location                VARCHAR(255),
    investment_grade               BOOLEAN       NOT NULL DEFAULT FALSE,
    sp_rating                      VARCHAR(50)   NOT NULL DEFAULT '',
    moodys_rating                  VARCHAR(50)   NOT NULL DEFAULT '',
    fitch_rating                   VARCHAR(50)   NOT NULL DEFAULT '',
    aum                            VARCHAR(50),
    nav                            VARCHAR(50),
    pension_assets                 VARCHAR(50),
    funding_ratio                  NUMERIC(7, 4),
    ubs_lp_category                VARCHAR(255),
    ubs_default_advance_rate       NUMERIC(7, 4),
    -- Mirrors lp_records.ubs_concentration_limit exactly, including the percent-or-dollars
    -- magnitude split, so the write-back round-trips a $25M cap NUMERIC(7,4) could not hold.
    ubs_default_concentration_limit NUMERIC(20, 2),
    notes                          TEXT,
    created_at                     TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at                     TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE lp_records (
    id                        SERIAL PRIMARY KEY,
    facility_id               INTEGER      NOT NULL REFERENCES facilities(id),
    lp_master_id              INTEGER      REFERENCES lp_master(id),
    investor_name             VARCHAR(255) NOT NULL,
    parent                    VARCHAR(255),
    spv                       BOOLEAN      NOT NULL DEFAULT FALSE,
    high_quality              BOOLEAN      NOT NULL DEFAULT TRUE,
    investor_type             VARCHAR(255) NOT NULL,
    institutional_or_hnw      VARCHAR(255) NOT NULL DEFAULT 'Institutional',
    region_location           VARCHAR(255) NOT NULL,
    investment_grade          BOOLEAN      NOT NULL DEFAULT FALSE,
    ubs_lp_category           VARCHAR(255) NOT NULL,
    ubs_lp_category_tag       VARCHAR(255),
    agent_lp_category         VARCHAR(255),
    agent_lp_category_source  VARCHAR(20),
    sp_rating                 VARCHAR(50)  NOT NULL DEFAULT '',
    moodys_rating             VARCHAR(50)  NOT NULL DEFAULT '',
    fitch_rating              VARCHAR(50)  NOT NULL DEFAULT '',
    aum                       VARCHAR(50),
    nav                       VARCHAR(50),
    pension_assets            VARCHAR(50),
    funding_ratio             NUMERIC(7, 4),
    capital_commitment        NUMERIC(20, 2),
    pct_of_fund_commitments   NUMERIC(7, 4),
    called_capital            NUMERIC(20, 2),
    uncalled_capital          NUMERIC(20, 2),
    pct_of_fund_uncalled      NUMERIC(7, 4),
    pct_lp_called             NUMERIC(7, 4),
    agent_concentration_limit NUMERIC(20, 2),
    ubs_concentration_limit   NUMERIC(20, 2),
    agent_excess_concentration NUMERIC(20, 2),
    ubs_excess_concentration  NUMERIC(20, 2),
    agent_advance_rate        NUMERIC(7, 4),
    ubs_advance_rate          NUMERIC(7, 4),
    agent_borrowing_base      NUMERIC(20, 2),
    ubs_borrowing_base        NUMERIC(20, 2),
    included                  BOOLEAN      NOT NULL DEFAULT TRUE,
    reclassified              BOOLEAN      NOT NULL DEFAULT FALSE,
    recallable_distributions  NUMERIC(20, 2),
    transferee                BOOLEAN      NOT NULL DEFAULT FALSE,
    lp_rank                   INTEGER,
    source_seq                INTEGER,
    notes                     TEXT,
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_lp_records_agent_lp_category_source
        CHECK (agent_lp_category_source IS NULL
            OR agent_lp_category_source IN ('EXTRACTED', 'DERIVED', 'USER_EDITED'))
);

CREATE INDEX idx_lp_records_lp_master ON lp_records(lp_master_id);
    CREATE INDEX idx_lp_records_facility_investor ON lp_records (facility_id, investor_name);

CREATE TABLE bb_snapshots (
    id             SERIAL PRIMARY KEY,
    facility_id    INTEGER   NOT NULL REFERENCES facilities(id),
    calculated_by  INTEGER   REFERENCES users(id),
    calculated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    result         JSONB     NOT NULL
);

-- One row per generated report. facility_name is denormalized so history entries
-- survive facility deletion; facility_id is only a soft link.
CREATE TABLE report_history (
    id             SERIAL PRIMARY KEY,
    report         VARCHAR(100) NOT NULL,
    facility_id    INTEGER REFERENCES facilities(id) ON DELETE SET NULL,
    facility_name  VARCHAR(255),
    snapshot_label VARCHAR(100),
    format         VARCHAR(20),
    user_name      VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_report_history_created_at ON report_history(created_at DESC);

CREATE TABLE config (
    key        VARCHAR(100) PRIMARY KEY,
    value      JSONB        NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- wizard_step mirrors the 1-indexed WIZARD_STEPS array in the UI:
--   1 = Select Facility / Upload (default; also set when extraction fails with status='Error')
--   2 = Upload Document — transient; extraction runs inline so submissions jump directly to 3
--   3 = Review Extraction (status='Review'; awaiting credit officer action)
--   4 = Review Matches   (after POST /{id}/confirm)
--   5 = LP Category & Rate Assignment (after PATCH /{id}/shadow-bb-state)
-- shadow_bb_overrides: JSONB map of LP key → {cls, rate} overrides committed on Step 5
--
-- Independent-review (maker-checker) workflow for Shadow BB acceptance: a completed Shadow BB
-- no longer transitions the facility straight to Active. The operator (Analyst) submits it for
-- independent review (status='Pending Review'), and only an Account/Transaction Manager may
-- accept or reject it. The accepting manager must not be the maker: submitted_by (maker) and
-- reviewed_by (checker) are recorded as stable authentication identities (uuName), never as
-- foreign keys into users (see RBAC_ROLES.md). Attribution must survive independently of the
-- directory: a run's maker/checker is a permanent fact about that run, and must not change or
-- dangle if the directory row is later removed. Join to users on uu_name only to render a name.
--   submitted_by  — uuName/identity of the operator who submitted the run for review (maker)
--   reviewed_by   — uuName/identity of the manager who accepted or rejected it (checker)
--   review_note   — reviewer rationale, required on rejection
CREATE TABLE submissions (
    id                  SERIAL PRIMARY KEY,
    facility_id         INTEGER      NOT NULL REFERENCES facilities(id),
    agent_bank          VARCHAR(255) NOT NULL,
    period_month        VARCHAR(20)  NOT NULL,
    status              VARCHAR(50)  NOT NULL DEFAULT 'Processing',
    file_name           VARCHAR(255) NOT NULL,
    file_path           VARCHAR(512),
    uploaded_by         INTEGER REFERENCES users(id),
    -- Ownership captured at upload (RBAC_ROLES.md: "Upload establishes ownership from authenticated
    -- uuName"). owner_uu_name is the stable ownership key; owner_name is the display name captured
    -- at upload so "Submitted By" can render without a user directory.
    owner_uu_name       VARCHAR(255),
    owner_name          VARCHAR(255),
    notes               TEXT,
    wizard_step         INTEGER      NOT NULL DEFAULT 1,
    -- Optimistic-concurrency token (JPA @Version): bumped on every write so a stale writer (e.g.
    -- the same submission edited in two tabs) is rejected with 409 instead of overwriting newer work.
    version             BIGINT       NOT NULL DEFAULT 0,
    shadow_bb_overrides JSONB,
    submitted_by        VARCHAR(255),
    reviewed_by         VARCHAR(255),
    review_note         TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_log (
    id          SERIAL PRIMARY KEY,
    event       VARCHAR(100) NOT NULL,
    detail      TEXT,
    facility_id INTEGER REFERENCES facilities(id),
    user_id     INTEGER REFERENCES users(id),
    user_name   VARCHAR(100),
    user_display VARCHAR(255),
    ip          VARCHAR(45),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_created_at ON audit_log(created_at DESC);

-- ── LP Rates feed ──────────────────────────────────────────────────────────────
-- Stores one row per LP per effective period; the most recent row on or before
-- the submission date is used by the Run Shadow BB calculation.
-- Rates are stored as decimals (0.9000 = 90%) — NOT formatted strings.

CREATE TABLE lp_rates (
    id                  SERIAL        PRIMARY KEY,
    lp_id               INTEGER       NOT NULL REFERENCES lp_records(id) ON DELETE CASCADE,
    effective_date      DATE          NOT NULL,
    classification      VARCHAR(50)   NOT NULL,
    ubs_adv_rate_pct    NUMERIC(7,4)  NOT NULL,
    ubs_conc_limit_pct  NUMERIC(7,4)  NOT NULL,
    source              VARCHAR(50)   NOT NULL DEFAULT 'BATCH_FEED',
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_lp_rates_lp_date UNIQUE (lp_id, effective_date)
);

CREATE INDEX idx_lp_rates_effective_date ON lp_rates (effective_date);
CREATE INDEX idx_lp_rates_lp_id          ON lp_rates (lp_id);

-- ── Extraction & match-queue tables ───────────────────────────────────────────

-- forced_template: operator-forced Agent BB template; null = auto-detect.
-- When auto-detection picks the wrong fund template the operator picks the correct
-- format from the dropdown; persisted here so every re-extraction re-applies it.
-- template_version stores the recognised fund template name (bb_templates.template_name
-- is VARCHAR(255)); template_format mirrors it for structural fallbacks.
CREATE TABLE submission_extractions (
    id                   SERIAL PRIMARY KEY,
    submission_id        INTEGER NOT NULL REFERENCES submissions(id),
    template_format      VARCHAR(255),
    template_version     VARCHAR(255),
    sheet_name           VARCHAR(255),
    header_row_index     INTEGER,
    total_rows           INTEGER NOT NULL DEFAULT 0,
    flagged_count        INTEGER NOT NULL DEFAULT 0,
    extracted_lps        JSONB,
    field_mappings       JSONB,
    unrecognized_columns JSONB,
    forced_template      VARCHAR(120),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_submission_extractions_submission ON submission_extractions(submission_id);

-- match_details: structured Match Analysis payload (Solution Design §6.5) — normalised
--   agent name, winning confidence band, and ranked top-5 LP Master candidates with their
--   Jaro-Winkler, Levenshtein and combined scores; drives the Match Analysis UI panel.
CREATE TABLE match_queue_entries (
    id                   SERIAL PRIMARY KEY,
    submission_id        INTEGER NOT NULL REFERENCES submissions(id),
    facility_id          INTEGER NOT NULL REFERENCES facilities(id),
    row_index            INTEGER NOT NULL,
    extracted_name       VARCHAR(255),
    matched_lp_id        INTEGER REFERENCES lp_records(id),
    matched_lp_name      VARCHAR(255),
    match_score          INTEGER,
    decision             VARCHAR(50) NOT NULL DEFAULT 'pending',
    master_name_override VARCHAR(255),
    agent_parent         VARCHAR(255),
    master_parent        VARCHAR(255),
    is_new               BOOLEAN NOT NULL DEFAULT FALSE,
    reasons              JSONB,
    match_details        JSONB,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_match_queue_submission ON match_queue_entries(submission_id);
CREATE INDEX idx_match_queue_facility   ON match_queue_entries(facility_id);

-- ── Field Mapping Dictionary ───────────────────────────────────────────────────
-- is_derived: TRUE = agent-calculated output captured for display / cross-check only;
--             not fed into UBS's own BB engine as a raw input.
-- Alias resolution precedence: bank-specific aliases checked before Core; derived-field
-- aliases checked before the blocklist (so "Eligible Commitment" → Agent Eligible
-- Commitment wins over the "Eligible" blocklist entry guarding Uncalled Capital).

CREATE TABLE fm_canonical_fields (
    id              SERIAL PRIMARY KEY,
    group_name      VARCHAR(100) NOT NULL,
    group_sort      INTEGER      NOT NULL,
    field_sort      INTEGER      NOT NULL,
    canonical       VARCHAR(200) NOT NULL UNIQUE,
    lp_master_field VARCHAR(300) NOT NULL,
    disambiguation  TEXT,
    extraction_key  VARCHAR(50),
    is_derived      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_fm_canonical_fields_extraction_key
    ON fm_canonical_fields(extraction_key)
    WHERE extraction_key IS NOT NULL;

CREATE TABLE fm_aliases (
    id                 SERIAL PRIMARY KEY,
    canonical_field_id INTEGER      NOT NULL REFERENCES fm_canonical_fields(id),
    alias_sort         INTEGER      NOT NULL,
    alias_text         VARCHAR(200) NOT NULL,
    tier               VARCHAR(20)  NOT NULL DEFAULT 'Core',
    bank               VARCHAR(100)
);

CREATE TABLE fm_blocklist (
    id        SERIAL PRIMARY KEY,
    qualifier VARCHAR(100) NOT NULL UNIQUE,
    reason    TEXT NOT NULL
);

CREATE TABLE fm_suggestions (
    id               SERIAL PRIMARY KEY,
    extracted_header VARCHAR(200) NOT NULL,
    canonical_field  VARCHAR(200) NOT NULL,
    suggested_by     VARCHAR(100),
    source           VARCHAR(20)  NOT NULL DEFAULT 'User',
    confidence       INTEGER,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ── BB template registry ───────────────────────────────────────────────────────
-- bb_templates: one row per named template variant; top-level structural flags tell
--   the extraction engine how to interpret the workbook before column parsing begins.
-- bb_template_tabs: one row per Excel tab per template; drives per-tab extraction.
-- bb_template_groups: group-header rows (e.g. "Rated Investors") that set the
--   inherited LP Category for all LP rows beneath them.
--
-- template_class encodes the structural variant so the unique key is
-- (template_name, template_class) — a template may have more than one distinct layout:
--   Class A — Full BB Schedule, group-header classification, numerical ratings,
--              Tranche A / Tranche B summary; colour-coded RCL / transferee rows.
--   Class B — Full BB Schedule, per-row "Investor Category" column, single summary table.
--   Class C — Simplified Callable Capital; no ratings or advance-rate columns;
--              binary included / excluded logic only (SVB / First Citizens format).
CREATE TABLE bb_templates (
    id                        SERIAL PRIMARY KEY,
    template_name             VARCHAR(255) NOT NULL,
    template_class            VARCHAR(10)  NOT NULL DEFAULT 'A',
    sheet_name                VARCHAR(255),
    header_row_index          INTEGER,
    auto_learned              BOOLEAN      NOT NULL DEFAULT TRUE,
    tranche_count             INTEGER      NOT NULL DEFAULT 1,
    has_grouping_rows         BOOLEAN      NOT NULL DEFAULT FALSE,
    has_color_flags           BOOLEAN      NOT NULL DEFAULT FALSE,
    summary_rows_above_header INTEGER      NOT NULL DEFAULT 0,
    auto_discover_tabs        BOOLEAN      NOT NULL DEFAULT FALSE,
    template_slug             VARCHAR(50),
    agent_name                VARCHAR(255),
    title_row                 INTEGER,
    title_text                TEXT,
    summary_row_range         VARCHAR(20),
    detect_keys               JSONB        NOT NULL DEFAULT '[]',
    legend                    JSONB        NOT NULL DEFAULT '[]',
    notes                     JSONB        NOT NULL DEFAULT '[]',
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_bb_templates_name_class
    ON bb_templates (LOWER(template_name), template_class);

CREATE UNIQUE INDEX idx_bb_templates_slug
    ON bb_templates (template_slug);

-- tab_role values:
--   LP_GRID       Primary LP grid (commitments, ratings, advance rates) — main extraction target
--   CONCENTRATION Concentration cap and haircut schedules
--   CAPITAL_CALL  Capital call log / roll-forward audit trail
--   TOP_SHEET     Master certificate / summary (cross-check only; not parsed for LP data)
--
-- header_row_span: stacked column headers occupy more than one physical row; the engine
--   joins this many consecutive rows into one logical header.
-- skip_row_keywords: rows whose first populated cell matches any keyword (case-insensitive)
--   are discarded before LP parsing.

CREATE TABLE bb_template_tabs (
    id                SERIAL PRIMARY KEY,
    template_id       INTEGER      NOT NULL REFERENCES bb_templates(id) ON DELETE CASCADE,
    tab_role          VARCHAR(50)  NOT NULL,
    tab_sort          INTEGER      NOT NULL DEFAULT 1,
    sheet_name        VARCHAR(255),
    sleeve_name       VARCHAR(255),
    header_row_index  INTEGER,
    header_row_span   INTEGER      NOT NULL DEFAULT 1,
    skip_row_keywords JSONB        NOT NULL
        DEFAULT '["Total","Subtotal","Sub-Total","Grand Total","Sum","Net Total"]',
    -- Ordered column header strings exactly as they appear in the workbook;
    -- drives recognition column-fingerprint matching and the registry display.
    columns           JSONB        NOT NULL DEFAULT '[]',
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),

    -- Multiple LP_GRID rows per template (one per sleeve/borrower tab), so
    -- uniqueness is by (template_id, tab_sort) rather than (template_id, tab_role).
    CONSTRAINT uq_template_tab_sort UNIQUE (template_id, tab_sort),
    CONSTRAINT chk_tab_role CHECK (tab_role IN ('LP_GRID','CONCENTRATION','CAPITAL_CALL','TOP_SHEET'))
);

CREATE INDEX idx_bb_template_tabs_template ON bb_template_tabs(template_id);

-- LP Category resolution for group-header rows:
--   1. Per-row column present in sheet (e.g. WF "Investor Category") — highest priority
--   2. Inherited from current group context (this table) — when no column present
--   3. NULL — final fallback; surfaced as unresolved in ExtractionPreview
--
-- group_sort reflects the top-to-bottom order groups appear in the workbook.

CREATE TABLE bb_template_groups (
    id             SERIAL PRIMARY KEY,
    tab_id         INTEGER      NOT NULL REFERENCES bb_template_tabs(id) ON DELETE CASCADE,
    group_sort     INTEGER      NOT NULL,
    header_text    VARCHAR(255) NOT NULL,
    classification VARCHAR(100) NOT NULL,

    CONSTRAINT uq_template_group_header UNIQUE (tab_id, header_text)
);

CREATE INDEX idx_bb_template_groups_tab ON bb_template_groups(tab_id);
