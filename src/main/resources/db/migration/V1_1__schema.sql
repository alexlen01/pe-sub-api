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
