-- LP Rates feed table.
-- Populated by a batch ingestion process when the rates file arrives (typically monthly).
-- Stores one row per LP per effective period; the most recent row on or before the
-- submission date is used by the Run Shadow BB calculation.
--
-- Rates are stored as decimals (0.9000 = 90%, 0.0750 = 7.5%) — NOT formatted strings.

CREATE TABLE lp_rates (
    id                  SERIAL        PRIMARY KEY,
    lp_id               INTEGER       NOT NULL REFERENCES lps(id) ON DELETE CASCADE,
    effective_date      DATE          NOT NULL,
    classification      VARCHAR(50)   NOT NULL,
    ubs_adv_rate_pct    NUMERIC(7,4)  NOT NULL,   -- e.g. 0.9000 for 90%
    ubs_conc_limit_pct  NUMERIC(7,4)  NOT NULL,   -- e.g. 0.0750 for 7.5%
    source              VARCHAR(50)   NOT NULL DEFAULT 'BATCH_FEED',
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_lp_rates_lp_date UNIQUE (lp_id, effective_date)
);

CREATE INDEX idx_lp_rates_effective_date ON lp_rates (effective_date);
CREATE INDEX idx_lp_rates_lp_id          ON lp_rates (lp_id);

-- ── Config ────────────────────────────────────────────────────────────────────
-- Update agent_tiers to 5-tier scale matching BUSA (90, 75, 65, 50, 0)

UPDATE config
SET    value = '[
  {"cls":"Rated",              "rate":90},
  {"cls":"Unrated AUM >$2bn",  "rate":75},
  {"cls":"Unrated AUM $1-2bn", "rate":65},
  {"cls":"Eligible <$1bn",     "rate":50},
  {"cls":"Excluded",           "rate":0}
]',
       updated_at = NOW()
WHERE  key = 'agent_tiers';
