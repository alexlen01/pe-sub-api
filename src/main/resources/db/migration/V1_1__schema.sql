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

CREATE TABLE fm_canonical_fields (
    id              SERIAL PRIMARY KEY,
    group_name      VARCHAR(100) NOT NULL,
    group_sort      INTEGER      NOT NULL,
    field_sort      INTEGER      NOT NULL,
    canonical       VARCHAR(200) NOT NULL UNIQUE,
    lp_master_field VARCHAR(300) NOT NULL,
    disambiguation  TEXT,
    extraction_key  VARCHAR(50)
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

CREATE TABLE bb_templates (
    id               SERIAL PRIMARY KEY,
    agent_bank       VARCHAR(255) NOT NULL,
    sheet_name       VARCHAR(255),
    header_row_index INTEGER,
    auto_learned     BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_bb_templates_agent_bank ON bb_templates (LOWER(agent_bank));
