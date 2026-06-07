-- ── Core tables ───────────────────────────────────────────────────────────────

CREATE TABLE users (
    id         SERIAL PRIMARY KEY,
    email      VARCHAR(255) NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    role       VARCHAR(50)  NOT NULL DEFAULT 'Analyst',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE facilities (
    id           SERIAL PRIMARY KEY,
    name         VARCHAR(255)   NOT NULL UNIQUE,
    agent_bank   VARCHAR(255)   NOT NULL,
    status       VARCHAR(50)    NOT NULL DEFAULT 'Not Started',
    conc_limit_m NUMERIC(10, 2) NOT NULL DEFAULT 25,
    last_run_at  TIMESTAMP,
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE TABLE lps (
    id             SERIAL PRIMARY KEY,
    facility_id    INTEGER      NOT NULL REFERENCES facilities(id),
    rank           INTEGER      NOT NULL,
    name           VARCHAR(255) NOT NULL,
    parent         VARCHAR(255),
    spv            BOOLEAN      NOT NULL DEFAULT FALSE,
    hq             BOOLEAN      NOT NULL DEFAULT TRUE,
    type           VARCHAR(50)  NOT NULL,
    region         VARCHAR(100) NOT NULL,
    ig             BOOLEAN      NOT NULL DEFAULT FALSE,
    cls            VARCHAR(50)  NOT NULL,
    cls_tag        VARCHAR(50),
    sp             VARCHAR(20)  NOT NULL DEFAULT 'NR',
    mdy            VARCHAR(20)  NOT NULL DEFAULT 'NR',
    fitch          VARCHAR(20)  NOT NULL DEFAULT 'NR',
    aum            VARCHAR(50),
    nav            VARCHAR(50),
    pension        VARCHAR(50),
    pension_funded VARCHAR(50),
    cap_commit     VARCHAR(50),
    pct_cap_commit VARCHAR(20),
    called_cap     VARCHAR(50),
    uc             VARCHAR(50),
    pct_uncalled   VARCHAR(20),
    pct_called     VARCHAR(20),
    agent_conc     VARCHAR(20),
    ubs_conc       VARCHAR(20),
    agent_rate     VARCHAR(20),
    abb            VARCHAR(50),
    inc            BOOLEAN      NOT NULL DEFAULT TRUE,
    rcl            BOOLEAN      NOT NULL DEFAULT FALSE,
    tf             BOOLEAN      NOT NULL DEFAULT FALSE,
    notes          TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE bb_snapshots (
    id             SERIAL PRIMARY KEY,
    facility_id    INTEGER   NOT NULL REFERENCES facilities(id),
    calculated_by  INTEGER   REFERENCES users(id),
    calculated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    result         JSONB     NOT NULL
);

CREATE TABLE config (
    key        VARCHAR(100) PRIMARY KEY,
    value      JSONB        NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE submissions (
    id           SERIAL PRIMARY KEY,
    facility_id  INTEGER      NOT NULL REFERENCES facilities(id),
    agent_bank   VARCHAR(255) NOT NULL,
    period_month VARCHAR(20)  NOT NULL,
    status       VARCHAR(50)  NOT NULL DEFAULT 'Processing',
    file_name    VARCHAR(255) NOT NULL,
    file_path    VARCHAR(512),
    uploaded_by  INTEGER REFERENCES users(id),
    notes        TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_log (
    id          SERIAL PRIMARY KEY,
    event       VARCHAR(100) NOT NULL,
    detail      TEXT,
    facility_id INTEGER REFERENCES facilities(id),
    user_id     INTEGER REFERENCES users(id),
    user_name   VARCHAR(100),
    ip          VARCHAR(45),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_created_at ON audit_log(created_at DESC);

-- ── Extraction & match-queue tables ───────────────────────────────────────────

CREATE TABLE submission_extractions (
    id                   SERIAL PRIMARY KEY,
    submission_id        INTEGER NOT NULL REFERENCES submissions(id),
    template_format      VARCHAR(50),
    template_version     VARCHAR(50),
    sheet_name           VARCHAR(255),
    header_row_index     INTEGER,
    total_rows           INTEGER NOT NULL DEFAULT 0,
    flagged_count        INTEGER NOT NULL DEFAULT 0,
    extracted_lps        JSONB,
    field_mappings       JSONB,
    unrecognized_columns JSONB,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_submission_extractions_submission ON submission_extractions(submission_id);

CREATE TABLE match_queue_entries (
    id                   SERIAL PRIMARY KEY,
    submission_id        INTEGER NOT NULL REFERENCES submissions(id),
    facility_id          INTEGER NOT NULL REFERENCES facilities(id),
    row_index            INTEGER NOT NULL,
    extracted_name       VARCHAR(255),
    matched_lp_id        INTEGER REFERENCES lps(id),
    matched_lp_name      VARCHAR(255),
    match_score          INTEGER,
    decision             VARCHAR(50) NOT NULL DEFAULT 'pending',
    master_name_override VARCHAR(255),
    is_new               BOOLEAN NOT NULL DEFAULT FALSE,
    reasons              JSONB,
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
-- bb_templates: one row per agent bank; top-level structural flags tell the
--   extraction engine how to interpret the workbook before column parsing begins.
-- bb_template_tabs: one row per Excel tab per template; drives per-tab extraction.
-- bb_template_groups: group-header rows (e.g. "Rated Investors") that set the
--   inherited LP Classification for all LP rows beneath them.

CREATE TABLE bb_templates (
    id                       SERIAL PRIMARY KEY,
    agent_bank               VARCHAR(255) NOT NULL,
    -- sheet_name / header_row_index kept as a single-tab shortcut; superseded by
    -- bb_template_tabs for multi-tab workbooks.
    sheet_name               VARCHAR(255),
    header_row_index         INTEGER,
    auto_learned             BOOLEAN      NOT NULL DEFAULT TRUE,
    tranche_count            INTEGER      NOT NULL DEFAULT 1,
    has_grouping_rows        BOOLEAN      NOT NULL DEFAULT FALSE,
    has_color_flags          BOOLEAN      NOT NULL DEFAULT FALSE,
    summary_rows_above_header INTEGER     NOT NULL DEFAULT 0,
    created_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_bb_templates_agent_bank ON bb_templates (LOWER(agent_bank));

-- tab_role values:
--   LP_GRID       Primary LP grid (commitments, ratings, advance rates) — main extraction target
--   CONCENTRATION Concentration cap and haircut schedules
--   CAPITAL_CALL  Capital call log / roll-forward audit trail
--   TOP_SHEET     Master certificate / summary (cross-check only; not parsed for LP data)
--
-- skip_row_keywords: rows whose first populated cell matches any keyword (case-insensitive)
--   are discarded before LP parsing. Default covers most agent templates; override per tab.

CREATE TABLE bb_template_tabs (
    id               SERIAL PRIMARY KEY,
    template_id      INTEGER      NOT NULL REFERENCES bb_templates(id) ON DELETE CASCADE,
    tab_role         VARCHAR(50)  NOT NULL,
    tab_sort         INTEGER      NOT NULL DEFAULT 1,
    sheet_name       VARCHAR(255),
    header_row_index INTEGER,
    skip_row_keywords JSONB       NOT NULL
        DEFAULT '["Total","Subtotal","Sub-Total","Grand Total","Sum","Net Total"]',
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_template_tab_role UNIQUE (template_id, tab_role),
    CONSTRAINT chk_tab_role CHECK (tab_role IN ('LP_GRID','CONCENTRATION','CAPITAL_CALL','TOP_SHEET'))
);

CREATE INDEX idx_bb_template_tabs_template ON bb_template_tabs(template_id);

-- LP Classification resolution for group-header rows:
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
