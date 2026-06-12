-- Simulated LP rates feed — effective 2025-01-01.
-- Back-dated to Jan 2025 so the findLatestAsOf(asOf) query returns these rows
-- for any test submission with period >= 2025-01.  In production this table is
-- populated by the monthly BATCH_FEED; source = 'SIMULATED' marks these rows.
--
-- cls keys must match BbCalculationService.BUSA_RATES exactly (note en dash in
-- 'Unrated 1–2bn'). Any cls value outside the five canonical tiers is treated as
-- Eligible (50% / 7.5%) via the ELSE branches.
--
-- ubs_adv_rate_pct  : decimal fraction, e.g. 0.9000 = 90%
-- ubs_conc_limit_pct: per-LP cap as fraction of total eligible uncalled
--   Rated          → 15.00%  (full single-LP cap; highest-quality counterparties)
--   Unrated >$2bn  → 12.50%  (large unrated; modest haircut vs rated)
--   Unrated $1–2bn → 10.00%  (mid-tier unrated)
--   Eligible <$1bn →  7.50%  (small qualifying LPs; standard minimum)
--   Excluded       →  0.00%  (ineligible; no BB credit)

INSERT INTO lp_rates (
    lp_id,
    effective_date,
    classification,
    ubs_adv_rate_pct,
    ubs_conc_limit_pct,
    source
)
SELECT
    id,
    '2025-01-01'::DATE,
    cls,
    CASE cls
        WHEN 'Rated'          THEN 0.9000
        WHEN 'Unrated >2bn'   THEN 0.7500
        WHEN 'Unrated 1–2bn'  THEN 0.6500
        WHEN 'Eligible'       THEN 0.5000
        WHEN 'Excluded'       THEN 0.0000
        ELSE                       0.5000
    END,
    CASE cls
        WHEN 'Rated'          THEN 0.1500
        WHEN 'Unrated >2bn'   THEN 0.1250
        WHEN 'Unrated 1–2bn'  THEN 0.1000
        WHEN 'Eligible'       THEN 0.0750
        WHEN 'Excluded'       THEN 0.0000
        ELSE                       0.0750
    END,
    'SIMULATED'
FROM lps
ON CONFLICT (lp_id, effective_date) DO NOTHING;
