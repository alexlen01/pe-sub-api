-- Change audit-retention value from the string "7 years" to the integer 7.
-- The unit (years) is implied; the UI renders a labelled dropdown.
UPDATE config SET
  value = '[
    {"id":"snapshot-freq",     "label":"Default Snapshot Frequency",                  "value":"Monthly (last business day)"},
    {"id":"match-auto-accept", "label":"Match Confidence Threshold (auto-accept)",    "value":95},
    {"id":"match-review",      "label":"Match Confidence Threshold (flag for review)","value":80},
    {"id":"report-watermark",  "label":"Report Watermark (default)",                  "value":"DRAFT - For Internal Review"},
    {"id":"audit-retention",   "label":"Audit Log Retention",                         "value":7}
  ]',
  updated_at = NOW()
WHERE key = 'global_settings';
