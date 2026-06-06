-- Change snapshot-freq value from the string "Monthly (last business day)" to the integer 30.
-- The unit is days; the UI renders a labelled dropdown.
UPDATE config SET
  value = '[
    {"id":"snapshot-freq",     "label":"Default Snapshot Frequency",                  "value":30},
    {"id":"match-auto-accept", "label":"Match Confidence Threshold (auto-accept)",    "value":95},
    {"id":"match-review",      "label":"Match Confidence Threshold (flag for review)","value":80},
    {"id":"report-watermark",  "label":"Report Watermark (default)",                  "value":"DRAFT - For Internal Review"},
    {"id":"audit-retention",   "label":"Audit Log Retention (years)",                 "value":7}
  ]',
  updated_at = NOW()
WHERE key = 'global_settings';
