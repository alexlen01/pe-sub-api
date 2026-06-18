-- ── Config ────────────────────────────────────────────────────────────────────

INSERT INTO config (key, value) VALUES ('busa_tiers', '[
  {"cls":"Rated",              "rate":90},
  {"cls":"Unrated AUM >$2bn",  "rate":75},
  {"cls":"Unrated AUM $1-2bn", "rate":65},
  {"cls":"Eligible <$1bn",     "rate":50},
  {"cls":"Excluded",           "rate":0}
]');

INSERT INTO config (key, value) VALUES ('agent_tiers', '[
  {"cls":"Rated",              "rate":90},
  {"cls":"Unrated AUM >$2bn",  "rate":75},
  {"cls":"Unrated AUM $1-2bn", "rate":65},
  {"cls":"Eligible <$1bn",     "rate":50},
  {"cls":"Excluded",           "rate":0}
]');

INSERT INTO config (key, value) VALUES ('agent_rate_params', '[
  {"label":"Minimum S&P Rating",     "value":"BBB-", "agency":"sp"},
  {"label":"Minimum Moody''s Rating", "value":"Baa3", "agency":"mdy"},
  {"label":"Minimum Fitch Rating",   "value":"BBB-", "agency":"fitch"},
  {"label":"Agent Unrated AUM Floor","value":1000000000}
]');

INSERT INTO config (key, value) VALUES ('elig_rules', '[
  {"id":"min-commit",   "rule":"Minimum LP Commitment",           "value":500000,   "unit":"$", "active":true},
  {"id":"max-conc",     "rule":"Maximum Single-LP Concentration", "value":15,       "unit":"%", "active":true},
  {"id":"erisa-cap",    "rule":"ERISA Plan Asset Cap",            "value":25,       "unit":"%", "active":true},
  {"id":"pension-conc", "rule":"Pension Fund Concentration",      "value":30,       "unit":"%", "active":true},
  {"id":"side-pocket",  "rule":"Side Pocket / Unfunded",          "value":"Exclude",            "active":true},
  {"id":"defaulted",    "rule":"Defaulted LP Exclusion",          "value":"Auto",               "active":true},
  {"id":"foreign-sov",  "rule":"Foreign Sovereign Exclusion",     "value":"Manual",             "active":false}
]');

INSERT INTO config (key, value) VALUES ('conc_limits', '[
  {"label":"Single LP max",           "value":15, "basis":"Total UBS BB"},
  {"label":"Top-10 LP max",           "value":60, "basis":"Total UBS BB"},
  {"label":"Unrated max (aggregate)", "value":50, "basis":"Total UBS BB"},
  {"label":"Non-US LP max",           "value":30, "basis":"Total UBS BB"},
  {"label":"Pension fund max",        "value":30, "basis":"Total eligible uncalled"}
]');

INSERT INTO config (key, value) VALUES ('global_settings', '[
  {"id":"snapshot-freq",     "label":"Default Snapshot Frequency",                  "value":30},
  {"id":"match-auto-accept", "label":"Match Confidence Threshold (auto-accept)",    "value":95},
  {"id":"match-review",      "label":"Match Confidence Threshold (flag for review)","value":80},
  {"id":"report-watermark",  "label":"Report Watermark (default)",                  "value":"DRAFT - For Internal Review"},
  {"id":"audit-retention",   "label":"Audit Log Retention (years)",                 "value":7}
]');

INSERT INTO config (key, value) VALUES ('matching_config', '{
  "thresholds": {
    "autoAccept":          95,
    "reviewQueue":         80,
    "noMatch":             50,
    "jwWeight":            0.6,
    "levWeight":           0.4,
    "stripSuffixes":       true,
    "caseFold":            true,
    "punctuation":         true,
    "abbrevExpand":        true,
    "retirementNormalize": true
  },
  "legalSuffixes": [
    { "abbr": "LP",   "full": "Limited Partnership",                   "strip": true  },
    { "abbr": "LLC",  "full": "Limited Liability Company",             "strip": true  },
    { "abbr": "Ltd",  "full": "Limited",                               "strip": true  },
    { "abbr": "Pte.", "full": "Private (Singapore)",                   "strip": true  },
    { "abbr": "LLP",  "full": "Limited Liability Partnership",         "strip": true  },
    { "abbr": "GmbH", "full": "Gesellschaft mit beschrankter Haftung", "strip": true  },
    { "abbr": "Mgmt", "full": "Management",                            "strip": false },
    { "abbr": "Inv.", "full": "Investments / Investors",               "strip": false }
  ],
  "knownAbbreviations": [
    { "token": "GIC",     "expansion": "Government Investment Corporation"             },
    { "token": "ADIA",    "expansion": "Abu Dhabi Investment Authority"                },
    { "token": "CPPIB",   "expansion": "Canada Pension Plan Investment Board"          },
    { "token": "OTPP",    "expansion": "Ontario Teachers Pension Plan"                 },
    { "token": "CalPERS", "expansion": "California Public Employees Retirement System" },
    { "token": "JPM",     "expansion": "JPMorgan Chase"                                },
    { "token": "BofA",    "expansion": "Bank of America"                               },
    { "token": "BAML",    "expansion": "Bank of America Merrill Lynch"                 },
    { "token": "WF",      "expansion": "Wells Fargo"                                   },
    { "token": "PNC",     "expansion": "PNC Bank"                                      }
  ]
}');

-- ── Field Mapping Dictionary: canonical fields ────────────────────────────────
-- 31 fields across 7 groups.
-- is_derived = TRUE: agent-calculated output; captured for display / cross-check
--                    only; not used as a raw input by the UBS BB engine.

INSERT INTO fm_canonical_fields
    (group_name, group_sort, field_sort, canonical, lp_master_field, disambiguation, extraction_key, is_derived)
VALUES

  -- ── Group 1: Identity & Classification ──────────────────────────────────────
  ('Identity & Classification', 1, 1,
      'Investor Name',
      'Identity & Classification - Investor Name',
      NULL,
      'INVESTOR_NAME', FALSE),

  ('Identity & Classification', 1, 2,
      'Investor Type',
      'Identity & Classification - Investor Type',
      'The agent''s own classification label, taken verbatim from the Agent BB document. '
          'May appear as a column OR as group-header rows that separate sections of LPs '
          '(e.g. "Rated Included", "Designated PWM"); when supplied as section rows, the '
          'value is filled down onto every LP beneath the header. Standard values: Rated '
          'Included, Non-Rated Included, Designated Institutional, Designated PWM, Largest 5 '
          'Designated, Aggregate Designated PWM. Do not normalise to the UBS tier.',
      'AGENT_LP_CLASSIFICATION', FALSE),

  ('Identity & Classification', 1, 3,
      'Transferee',
      'Identity & Classification - Transferee',
      'Y where LP received a transferred commitment; blank otherwise',
      NULL, FALSE),

  ('Identity & Classification', 1, 4,
      'Parent / Sponsor',
      'Identity & Classification - Parent / Sponsor',
      'Ultimate parent or sponsoring entity of the LP',
      NULL, FALSE),

  ('Identity & Classification', 1, 5,
      'Eligibility Flag',
      'Identity & Classification - Eligibility Flag',
      'Y/Eligible/Included vs N/Excluded; agent-assigned per-LP; treat as derived — WF template encodes this as a formula column separate from Investor Category',
      'ELIGIBILITY_FLAG', TRUE),

  -- ── Group 2: Commitment Data ─────────────────────────────────────────────────
  ('Commitment Data', 2, 1,
      'Capital Commitments',
      'Commitment Data - Capital Commitments',
      'Prefer "Individual" prefix column over aggregate when both present',
      'COMMITMENT', FALSE),

  ('Commitment Data', 2, 2,
      '% of Capital Commitments',
      'Commitment Data - % of Capital Commitments',
      'LP''s commitment as a percentage of total fund commitments',
      NULL, FALSE),

  ('Commitment Data', 2, 3,
      'Called Capital',
      'Commitment Data - Called Capital',
      'Cumulative capital drawn from the LP to date',
      NULL, FALSE),

  ('Commitment Data', 2, 4,
      'Recallable Distributions',
      'Commitment Data - Recallable Distributions',
      'Prior distributions subject to recall; SVB template feature; added back to callable capital base when computing remaining callable capital',
      'RECALLABLE_DIST', FALSE),

  -- ── Group 3: Uncalled Data ───────────────────────────────────────────────────
  ('Uncalled Data', 3, 1,
      'Uncalled Capital',
      'Uncalled Data - Uncalled Capital',
      'Prefer "Individual" prefix; skip column if any qualifier blocklist term matches header',
      'UNCALLED', FALSE),

  ('Uncalled Data', 3, 2,
      '% of Uncalled Capital',
      'Uncalled Data - % of Uncalled Capital',
      'LP''s uncalled capital as a percentage of total fund uncalled',
      NULL, FALSE),

  ('Uncalled Data', 3, 3,
      '% of LP Called',
      'Uncalled Data - % of LP Called',
      'Percentage of the LP''s own commitment that has been drawn',
      NULL, FALSE),

  -- ── Group 4: Financial Scale ─────────────────────────────────────────────────
  ('Financial Scale', 4, 1,
      'AUM',
      'Financial Scale - AUM',
      NULL,
      'AUM', FALSE),

  ('Financial Scale', 4, 2,
      'NAV',
      'Financial Scale - NAV',
      'Net asset value of the LP''s fund interest at most recent reporting date',
      NULL, FALSE),

  ('Financial Scale', 4, 3,
      'Pension Assets',
      'Financial Scale - Pension Assets',
      'Total pension fund assets managed by or on behalf of the LP',
      NULL, FALSE),

  ('Financial Scale', 4, 4,
      'Pension Funded %',
      'Financial Scale - Pension Funded %',
      'Funded status of the LP''s pension plan expressed as a percentage',
      NULL, FALSE),

  -- ── Group 5: Borrowing Base ──────────────────────────────────────────────────
  -- Field 1 is the credit-agreement parameter; fields 2–5 are agent-calculated
  -- outputs captured for display and cross-check against the UBS engine.

  ('Borrowing Base', 5, 1,
      'Advance Rate',
      'Borrowing Base - Advance Rate',
      NULL,
      'AGENT_RATE', FALSE),

  ('Borrowing Base', 5, 2,
      'Eligible Commitment',
      'Borrowing Base - Eligible Commitment',
      'LP uncalled commitment after per-LP concentration haircut applied; agent-calculated; maps to "Eligible Commitment" (GS/WF) and "Remaining Callable Capital Adjusted for Concentration Limit" (SVB)',
      NULL, TRUE),

  ('Borrowing Base', 5, 3,
      '% of Eligible Uncalled',
      'Borrowing Base - % of Eligible Uncalled',
      'LP eligible uncalled as % of total eligible uncalled pool; agent-calculated; appears as "% Eligible Unfunded Commitment" in GS and WF templates',
      NULL, TRUE),

  ('Borrowing Base', 5, 4,
      '% of Borrowing Base',
      'Borrowing Base - % of Borrowing Base',
      'LP BB contribution as % of total facility borrowing base; agent-calculated informational column',
      NULL, TRUE),

  ('Borrowing Base', 5, 5,
      'Borrowing Base',
      'Borrowing Base - Borrowing Base',
      'LP-level borrowing base as reported by the facility agent (= Eligible Commitment × Advance Rate)',
      NULL, TRUE),

  -- ── Group 6: Concentration ───────────────────────────────────────────────────
  ('Concentration', 6, 1,
      'Concentration Limit',
      'Concentration - Concentration Limit',
      NULL,
      'CONCENTRATION_LIMIT', FALSE),

  ('Concentration', 6, 2,
      'Concentration (%)',
      'Concentration - Concentration (%)',
      'LP''s concentration expressed as a percentage of the relevant base, as reported by the agent.',
      NULL, FALSE),

  ('Concentration', 6, 3,
      'Excess Concentration',
      'Concentration - Excess Concentration',
      'Dollar amount by which LP uncalled exceeds the per-LP concentration cap; agent-calculated as max(0, uncalled − cap × total_eligible)',
      NULL, TRUE),

  ('Concentration', 6, 4,
      'Excess Concentration (%)',
      'Concentration - Excess Concentration (%)',
      'Excess concentration expressed as a percentage; agent-calculated overage relative to the per-LP concentration cap.',
      NULL, TRUE),

  -- ── Group 7: Ratings ─────────────────────────────────────────────────────────
  -- Fields 1–3 are raw letter ratings; fields 4–6 are Goldman Sachs numeric
  -- conversions (0–9 scale) used in their advance rate tier lookup.

  ('Ratings', 7, 1,
      'S&P Rating',
      'Ratings - S&P Rating',
      'Use last occurrence when column header repeats in same sheet',
      NULL, FALSE),

  ('Ratings', 7, 2,
      'Moody''s Rating',
      'Ratings - Moody''s Rating',
      'Use last occurrence when column header repeats in same sheet',
      NULL, FALSE),

  ('Ratings', 7, 3,
      'Fitch Rating',
      'Ratings - Fitch Rating',
      NULL,
      NULL, FALSE),

  ('Ratings', 7, 4,
      'S&P Numeric Score',
      'Ratings - S&P Numeric Score',
      'Goldman Sachs 0–9 numeric conversion of S&P letter rating used in advance rate tier lookup; GS-specific derived column; do not confuse with raw S&P letter rating',
      NULL, TRUE),

  ('Ratings', 7, 5,
      'Moody''s Numeric Score',
      'Ratings - Moody''s Numeric Score',
      'Goldman Sachs 0–9 numeric conversion of Moody''s letter rating; GS-specific derived column',
      NULL, TRUE),

  ('Ratings', 7, 6,
      'Numeric Rating',
      'Ratings - Numeric Rating',
      'Goldman Sachs composite 0–9 score (higher of S&P / Moody''s numeric) that drives the advance rate tier; GS-specific; bank-scoped alias "Applicable Rating (numerical ratings scale, 0-9)" — distinct from BNY''s letter-rating alias "Applicable Rating" → Moody''s Rating',
      NULL, TRUE);

-- ── Field Mapping Dictionary: aliases ────────────────────────────────────────

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank) VALUES

  -- Investor Name
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 1, 'Investor Name',                 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 2, 'Investor Name (Agent Records)', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 3, 'Investor',                      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 4, 'LP Name',                       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 5, 'Limited Partner',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 6, 'Fund Investor',                 'Bank', 'SVB'),

  -- Investor Type
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 1, 'LP Type',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 2, 'Investor Type',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 3, 'Classification',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 4, 'Category',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 5, 'Investor Category','Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 6, 'LP Classification','Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 7, 'Entity Type',      'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 8, 'Investor Class',   'Bank', 'JPM'),

  -- Transferee
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 1, 'Transferee',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 2, 'Transfer Flag',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 3, 'Assignee',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 4, 'Assignment Flag', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 5, 'Transferred LP',  'Core', NULL),

  -- Parent / Sponsor
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 1, 'Parent / Sponsor',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 2, 'Parent',                     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 3, 'Sponsor',                    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 4, 'Parent Entity',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 5, 'Sponsoring Entity',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 6, 'Ultimate Parent',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 7, 'Parent Organization',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 8, 'Parent / Sponsor / Manager', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 9, 'Manager',                    'Core', NULL),

  -- Eligibility Flag  (WF)
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligibility Flag'), 1, 'Eligibility', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligibility Flag'), 2, 'Eligible',    'Bank', 'WF'),

  -- Capital Commitments
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 1, 'Capital Commitments',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 2, 'Committed Capital',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 3, 'Original Commitment',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 4, 'Individual Original Commitment',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 5, 'Total Commitment',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 6, 'Commitment (USD)',               'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 7, 'Total Capital Commitments ($)',  'Core', NULL),

  -- % of Capital Commitments
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 1, '% of Capital Commitments', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 2, '% Commitment',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 3, 'Commitment Percentage',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 4, 'LP Commitment %',          'Core', NULL),

  -- Called Capital
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 1, 'Called Capital',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 2, 'Drawn Capital',     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 3, 'Funded Capital',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 4, 'Capital Drawn',     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 5, 'Funded Commitment', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 6, 'Called Commitment', 'Core', NULL),

  -- Recallable Distributions  (SVB)
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Recallable Distributions'), 1, 'Recallable Distributions', 'Bank', 'SVB'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Recallable Distributions'), 2, 'Recallable Capital',       'Bank', 'SVB'),

  -- Uncalled Capital
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 1, 'Uncalled Capital',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 2, 'Unfunded Capital Commitment',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 3, 'Individual Unfunded Commitment', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 4, 'Unfunded Commitment',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 5, 'Remaining Callable Capital',     'Bank', 'SVB'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 6, 'Remaining Commitment',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 7, 'Uncalled Capital (USD)',          'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 8, 'Unfunded Capital Commitments ($)','Core', NULL),

  -- % of Uncalled Capital
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 1, '% of Uncalled Capital',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 2, '% Uncalled',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 3, 'Uncalled %',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 4, '% Unfunded',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 5, 'Uncalled Ratio',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 6, '% Total Unfunded Commitment', 'Core', NULL),

  -- % of LP Called
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 1, '% of LP Called', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 2, '% Called',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 3, 'Called Ratio',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 4, 'Draw Percentage', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 5, '% Funded',        'Core', NULL),

  -- AUM
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 1, 'AUM',                     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 2, 'Assets Under Management', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 3, 'Net Assets',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 4, 'Net Assets (range)',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 5, 'Total AUM',                'Core', NULL),

  -- NAV
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 1, 'NAV',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 2, 'Net Asset Value',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 3, 'Fund NAV',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 4, 'Total NAV',        'Core', NULL),

  -- Pension Assets
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 1, 'Pension Assets',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 2, 'Pension Fund Assets', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 3, 'Pension Pool',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 4, 'ERISA Assets',        'Core', NULL),

  -- Pension Funded %
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 1, 'Pension Funded %',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 2, 'Pension Funding Ratio', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 3, 'Funded Status',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 4, 'Pension Funded Ratio',  'Core', NULL),

  -- Advance Rate
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 1, 'Advance Rate',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 2, 'Agent Advance Rate', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 3, 'Adv. Rate',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 4, 'Rate',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 5, 'Applicable Rate',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 6, 'Advance Rate (%)',   'Core', NULL),

  -- Eligible Commitment
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligible Commitment'), 1, 'Eligible Commitment',                                        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligible Commitment'), 2, 'Remaining Callable Capital Adjusted for Concentration Limit', 'Bank', 'SVB'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligible Commitment'), 3, 'Eligible Uncalled',                                           'Core', NULL),

  -- % of Eligible Uncalled
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Eligible Uncalled'), 1, '% Eligible Unfunded Commitment', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Eligible Uncalled'), 2, '% of Eligible Uncalled Capital', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Eligible Uncalled'), 3, '% Eligible Uncalled',            'Core', NULL),

  -- % of Borrowing Base
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Borrowing Base'), 1, '% of Borrowing Base', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Borrowing Base'), 2, '% BB',                'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Borrowing Base'), 3, 'BB Percentage',       'Core', NULL),

  -- Borrowing Base
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 1, 'Agent Borrowing Base',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 2, 'Agent BB',                    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 3, 'Facility BB',                 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 4, 'Agent Base',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 5, 'BB Amount',                   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 6, 'Borrowing Base Contribution', 'Core', NULL),

  -- Concentration Limit
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 1, 'Concentration Limit',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 2, 'Agent Concentration Limit', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 3, 'Conc. Limit',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 4, 'Excel Concentration',       'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 5, 'Max Concentration',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 6, 'Aggregate Concentration',   'Core', NULL),

  -- Concentration (%)
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration (%)'), 1, 'Concentration (%)', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration (%)'), 2, 'Concentration %',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration (%)'), 3, 'LP Concentration',  'Core', NULL),

  -- Excess Concentration
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration'), 1, 'Excess Concentration', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration'), 2, 'Conc. Overage',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration'), 3, 'Concentration Excess', 'Core', NULL),

  -- Excess Concentration (%)
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration (%)'), 1, 'Excess Concentration (%)', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration (%)'), 2, 'Excess Concentration %',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration (%)'), 3, '% Excess Concentration',   'Core', NULL),

  -- S&P Rating
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 1, 'S&P',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 2, 'S&P Rating',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 3, 'S&P Credit Rating', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 4, 'S and P',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 5, 'S & P''s Rating',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 6, 'S & P',             'Core', NULL),

  -- Moody's Rating
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 1, 'Moody''s',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 2, 'Moody''s Rating',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 3, 'Moodys',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 4, 'Applicable Rating', 'Bank', 'BNY'),

  -- Fitch Rating
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 1, 'Fitch',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 2, 'Fitch Rating',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 3, 'Fitch Credit Rating', 'Core', NULL),

  -- S&P Numeric Score  (Goldman Sachs)
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Numeric Score'),      1, 'S&P (numerical ratings scale, 0-9)',      'Bank', 'GS'),

  -- Moody's Numeric Score  (Goldman Sachs)
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Numeric Score'), 1, 'Moody''s (numerical ratings scale, 0-9)', 'Bank', 'GS'),

  -- Numeric Rating  (Goldman Sachs)
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Numeric Rating'),    1, 'Applicable Rating (numerical ratings scale, 0-9)', 'Bank', 'GS');

-- ── Field Mapping Dictionary: blocklist ──────────────────────────────────────
-- Qualifiers that flag a column as post-processed; blocked from being selected
-- as the source for any raw-input canonical field (e.g. Uncalled Capital).
-- Derived-field aliases are evaluated BEFORE this list, so "Eligible Commitment"
-- correctly routes to Eligible Commitment rather than being discarded.

INSERT INTO fm_blocklist (qualifier, reason) VALUES
  ('Adjusted',            'Post-processed — concentration or eligibility already applied'),
  ('Eligible',            'Post-eligibility filter applied — not a raw input field'),
  ('Capped',              'Concentration cap already applied upstream'),
  ('Net of',              'Net value includes deductions — not a raw commitment'),
  ('Post-Haircut',        'Haircut already applied upstream'),
  ('After Concentration', 'Concentration already factored in');

-- ── Field Mapping Dictionary: suggestions ────────────────────────────────────

INSERT INTO fm_suggestions (extracted_header, canonical_field, suggested_by, source, confidence) VALUES
  ('Outstanding Callable Balance', 'Uncalled Capital', 'J. Martinez', 'User', NULL),
  ('Applicable Rating',            'Moody''s Rating',  'AI Engine',   'AI',   82);

-- ── BB template registry: known agent banks ───────────────────────────────────
-- sheet_name / header_row_index on bb_templates left NULL; canonical location
-- for per-tab sheet names is bb_template_tabs.

INSERT INTO bb_templates
    (agent_bank, tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header, auto_learned)
VALUES
    -- Goldman Sachs: 2 tranches (A + B), row-grouped by investor type,
    -- colour-coded LP flags (pink = Reclassified, blue = Transferee)
    ('Goldman Sachs Bank USA', 2, TRUE,  TRUE,  0, FALSE),

    -- SVB: single tranche, flat layout; fund name + reporting date rows
    -- appear above the column header (summary_rows_above_header = 2)
    ('Silicon Valley Bank',    1, FALSE, FALSE, 2, FALSE),

    -- Wells Fargo: single tranche; summary table above LP grid;
    -- header row auto-detected via header_row_index on the tab
    ('Wells Fargo',            1, FALSE, FALSE, 0, FALSE);

-- ── BB template tabs: LP_GRID tab for each known bank ─────────────────────────
-- sheet_name and header_row_index are NULL until confirmed from a real workbook;
-- the extraction service updates them on first successful parse.

INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index)
SELECT id, 'LP_GRID', 1, NULL, NULL
FROM   bb_templates
WHERE  agent_bank IN ('Goldman Sachs Bank USA', 'Silicon Valley Bank', 'Wells Fargo');

-- ── BB template groups: Goldman Sachs LP_GRID group headers ───────────────────
-- T1 layout (top → bottom): Rated Included → Non-Rated Included →
-- Designated Institutional → Excluded.
-- header_text MUST remain the literal text that appears in the agent's grouping
-- row; only the resolved classification value matters to the extraction service.

INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT t.id, g.group_sort, g.header_text, g.classification
FROM   bb_template_tabs t
JOIN   bb_templates     tmpl ON tmpl.id = t.template_id
CROSS JOIN (VALUES
    (1, 'Rated Investors',    'Rated Included'),
    (2, 'Unrated Investors',  'Non-Rated Included'),
    (3, 'Eligible Investors', 'Designated Institutional'),
    (4, 'Excluded Investors', 'Excluded')
) AS g(group_sort, header_text, classification)
WHERE  tmpl.agent_bank = 'Goldman Sachs Bank USA'
  AND  t.tab_role      = 'LP_GRID';

-- ── LP Rates: simulated feed — effective 2025-01-01 ───────────────────────────
-- Back-dated to Jan 2025 so findLatestAsOf(asOf) returns these rows for any
-- test submission with period >= 2025-01. In production this table is populated
-- by the monthly BATCH_FEED; source = 'SIMULATED' marks these rows.
--
-- ubs_adv_rate_pct  : decimal fraction (0.9000 = 90%)
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
FROM lp_records
ON CONFLICT (lp_id, effective_date) DO NOTHING;
