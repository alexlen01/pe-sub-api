-- Migrate config values: replace formatted strings with proper numeric types.
-- Percentage rates and thresholds become whole-number integers (90 = 90%).
-- Dollar amounts become raw integers (500000 = $500,000).
-- Text/mode values ("Exclude", "Auto", "BBB- / Baa3") are unchanged.
-- EligRule rows gain a "unit" field ('%' or '$') so callers know how to interpret numeric values.
--
-- V1_2 seed is left intact (Flyway checksum). This migration corrects both
-- existing databases (applies V1_4) and fresh databases (V1_2 seeds old format,
-- V1_4 immediately corrects it).

UPDATE config SET
  value = '[
    {"cls":"Rated",              "rate":90},
    {"cls":"Unrated AUM >$2bn",  "rate":75},
    {"cls":"Unrated AUM $1-2bn", "rate":65},
    {"cls":"Eligible <$1bn",     "rate":50},
    {"cls":"Excluded",           "rate":0}
  ]',
  updated_at = NOW()
WHERE key = 'busa_tiers';

UPDATE config SET
  value = '[
    {"cls":"Investment Grade", "rate":95},
    {"cls":"Non-IG Eligible",  "rate":75},
    {"cls":"Excluded",         "rate":0}
  ]',
  updated_at = NOW()
WHERE key = 'agent_tiers';

-- Row 0: text value stays as string. Row 1: AUM floor becomes raw integer.
UPDATE config SET
  value = '[
    {"label":"Minimum Rated Rating Threshold", "value":"BBB- / Baa3"},
    {"label":"Agent Unrated AUM Floor",        "value":1000000000}
  ]',
  updated_at = NOW()
WHERE key = 'agent_rate_params';

-- Numeric threshold rules gain a "unit" discriminator ('%' or '$').
-- Mode/behaviour rules ("Exclude", "Auto", "Manual") keep their string value; no unit field.
UPDATE config SET
  value = '[
    {"id":"min-commit",   "rule":"Minimum LP Commitment",           "value":500000, "unit":"$", "active":true},
    {"id":"max-conc",     "rule":"Maximum Single-LP Concentration", "value":15,     "unit":"%", "active":true},
    {"id":"erisa-cap",    "rule":"ERISA Plan Asset Cap",            "value":25,     "unit":"%", "active":true},
    {"id":"pension-conc", "rule":"Pension Fund Concentration",      "value":30,     "unit":"%", "active":true},
    {"id":"side-pocket",  "rule":"Side Pocket / Unfunded",          "value":"Exclude",          "active":true},
    {"id":"defaulted",    "rule":"Defaulted LP Exclusion",          "value":"Auto",             "active":true},
    {"id":"foreign-sov",  "rule":"Foreign Sovereign Exclusion",     "value":"Manual",           "active":false}
  ]',
  updated_at = NOW()
WHERE key = 'elig_rules';

UPDATE config SET
  value = '[
    {"label":"Single LP max",           "value":15, "basis":"Total UBS BB"},
    {"label":"Top-10 LP max",           "value":60, "basis":"Total UBS BB"},
    {"label":"Unrated max (aggregate)", "value":50, "basis":"Total UBS BB"},
    {"label":"Non-US LP max",           "value":30, "basis":"Total UBS BB"},
    {"label":"Pension fund max",        "value":30, "basis":"Total eligible uncalled"}
  ]',
  updated_at = NOW()
WHERE key = 'conc_limits';

-- Match threshold settings become integers (95, 80) consistent with matching_config.
-- Text settings are unchanged.
UPDATE config SET
  value = '[
    {"id":"snapshot-freq",     "label":"Default Snapshot Frequency",                  "value":"Monthly (last business day)"},
    {"id":"match-auto-accept", "label":"Match Confidence Threshold (auto-accept)",    "value":95},
    {"id":"match-review",      "label":"Match Confidence Threshold (flag for review)","value":80},
    {"id":"report-watermark",  "label":"Report Watermark (default)",                  "value":"DRAFT - For Internal Review"},
    {"id":"audit-retention",   "label":"Audit Log Retention",                         "value":"7 years"}
  ]',
  updated_at = NOW()
WHERE key = 'global_settings';
