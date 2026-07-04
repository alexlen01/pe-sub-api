-- ── Report history ─────────────────────────────────────────────────────────────
-- One row per generated report (Reports screen). facility_name is denormalised so
-- history entries survive facility deletion; facility_id is only a soft link.

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
