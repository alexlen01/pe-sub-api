-- ── Config ────────────────────────────────────────────────────────────────────

INSERT INTO config (key, value) VALUES ('busa_tiers', '[
  {"cls":"Rated",              "rate":90},
  {"cls":"Unrated AUM >$2bn",  "rate":75},
  {"cls":"Unrated AUM $1-2bn", "rate":65},
  {"cls":"Eligible <$1bn",     "rate":50},
  {"cls":"Excluded",           "rate":0}
]');

INSERT INTO config (key, value) VALUES ('agent_tiers', '[
  {"cls":"Investment Grade", "rate":95},
  {"cls":"Non-IG Eligible",  "rate":75},
  {"cls":"Excluded",         "rate":0}
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
    "autoAccept":    95,
    "reviewQueue":   80,
    "jwWeight":      0.6,
    "levWeight":     0.4,
    "stripSuffixes": true,
    "caseFold":      true,
    "punctuation":   true,
    "abbrevExpand":  true
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

INSERT INTO fm_canonical_fields
    (group_name, group_sort, field_sort, canonical, lp_master_field, disambiguation, extraction_key)
VALUES
  ('Identity & Classification', 1, 1, 'Investor Name',
      'Identity & Classification - Investor Name',     NULL,                                                                      'INVESTOR_NAME'),
  ('Identity & Classification', 1, 2, 'LP Classification',
      'Identity & Classification - LP Classification', 'Extract the Agent''s own category label as-is',                          'LP_CLASSIFICATION'),
  ('Identity & Classification', 1, 3, 'Transferee',
      'Identity & Classification - Transferee',        'Y where LP received a transferred commitment; blank otherwise',           NULL),
  ('Identity & Classification', 1, 4, 'Parent / Sponsor',
      'Identity & Classification - Parent / Sponsor',  'Ultimate parent or sponsoring entity of the LP',                         NULL),
  ('Commitment Data', 2, 1, 'Capital Commitments',
      'Commitment Data - Capital Commitments',          'Prefer "Individual" prefix column over aggregate when both present',     'COMMITMENT'),
  ('Commitment Data', 2, 2, '% of Capital Commitments',
      'Commitment Data - % of Capital Commitments',    'LP''s commitment as a percentage of total fund commitments',             NULL),
  ('Commitment Data', 2, 3, 'Called Capital',
      'Commitment Data - Called Capital',               'Cumulative capital drawn from the LP to date',                          NULL),
  ('Uncalled Data', 3, 1, 'Uncalled Capital',
      'Uncalled Data - Uncalled Capital',               'Prefer "Individual" prefix; skip column if any qualifier blocklist term matches header', 'UNCALLED'),
  ('Uncalled Data', 3, 2, '% of Uncalled Capital',
      'Uncalled Data - % of Uncalled Capital',          'LP''s uncalled capital as a percentage of total fund uncalled',         NULL),
  ('Uncalled Data', 3, 3, '% of LP Called',
      'Uncalled Data - % of LP Called',                 'Percentage of the LP''s own commitment that has been drawn',            NULL),
  ('Financial Scale', 4, 1, 'AUM',
      'Financial Scale - AUM',                          NULL,                                                                     'AUM'),
  ('Financial Scale', 4, 2, 'NAV',
      'Financial Scale - NAV',                          'Net asset value of the LP''s fund interest at most recent reporting date', NULL),
  ('Financial Scale', 4, 3, 'Pension Assets',
      'Financial Scale - Pension Assets',               'Total pension fund assets managed by or on behalf of the LP',           NULL),
  ('Financial Scale', 4, 4, 'Pension Funded %',
      'Financial Scale - Pension Funded %',             'Funded status of the LP''s pension plan expressed as a percentage',    NULL),
  ('Borrowing Base', 5, 1, 'Agent Advance Rate',
      'Borrowing Base - Agent Advance Rate',            NULL,                                                                     'AGENT_RATE'),
  ('Concentration',  6, 1, 'Agent Concentration Limit',
      'Concentration - Agent Concentration Limit',      NULL,                                                                     'CONCENTRATION_LIMIT'),
  ('Concentration',  6, 2, 'Agent Borrowing Base',
      'Concentration - Agent Borrowing Base',           'LP-level borrowing base as reported by the facility agent',             NULL),
  ('Ratings', 7, 1, 'S&P Rating',
      'Ratings - S&P Rating',                           'Use last occurrence when column header repeats in same sheet',          NULL),
  ('Ratings', 7, 2, 'Moody''s Rating',
      'Ratings - Moody''s Rating',                      'Use last occurrence when column header repeats in same sheet',          NULL),
  ('Ratings', 7, 3, 'Fitch Rating',
      'Ratings - Fitch Rating',                         'Use last occurrence when column header repeats in same sheet',          NULL);

-- ── Field Mapping Dictionary: aliases ────────────────────────────────────────

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank) VALUES
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 1, 'Investor Name',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 2, 'Investor Name (Agent Records)',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 3, 'Investor',                       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 4, 'LP Name',                        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 5, 'Limited Partner',                'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 6, 'Fund Investor',                  'Bank', 'SVB'),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Classification'), 1, 'LP Type',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Classification'), 2, 'Investor Type',     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Classification'), 3, 'Classification',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Classification'), 4, 'Category',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Classification'), 5, 'Investor Category', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Classification'), 6, 'LP Classification', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Classification'), 7, 'Entity Type',       'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Classification'), 8, 'Investor Class',    'Bank', 'JPM'),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 1, 'Transferee',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 2, 'Transfer Flag',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 3, 'Assignee',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 4, 'Assignment Flag', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 5, 'Transferred LP',  'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 1, 'Parent / Sponsor',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 2, 'Parent',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 3, 'Sponsor',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 4, 'Parent Entity',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 5, 'Sponsoring Entity',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 6, 'Ultimate Parent',     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 7, 'Parent Organization', 'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 1, 'Capital Commitments',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 2, 'Committed Capital',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 3, 'Original Commitment',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 4, 'Individual Original Commitment','Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 5, 'Total Commitment',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 6, 'Commitment (USD)',             'Bank', 'BNY'),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 1, '% of Capital Commitments', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 2, '% Commitment',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 3, 'Commitment Percentage',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 4, 'LP Commitment %',          'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 1, 'Called Capital',     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 2, 'Drawn Capital',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 3, 'Funded Capital',     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 4, 'Capital Drawn',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 5, 'Funded Commitment',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 6, 'Called Commitment',  'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 1, 'Uncalled Capital',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 2, 'Unfunded Capital Commitment',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 3, 'Individual Unfunded Commitment', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 4, 'Unfunded Commitment',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 5, 'Remaining Callable Capital',    'Bank', 'SVB'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 6, 'Remaining Commitment',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 7, 'Uncalled Capital (USD)',         'Bank', 'BNY'),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 1, '% of Uncalled Capital', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 2, '% Uncalled',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 3, 'Uncalled %',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 4, '% Unfunded',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 5, 'Uncalled Ratio',        'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 1, '% of LP Called', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 2, '% Called',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 3, 'Called Ratio',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 4, 'Draw Percentage', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 5, '% Funded',        'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 1, 'AUM',                    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 2, 'Assets Under Management','Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 3, 'Net Assets',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 4, 'Net Assets (range)',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 5, 'Total AUM',               'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 1, 'NAV',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 2, 'Net Asset Value','Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 3, 'Fund NAV',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 4, 'Total NAV',      'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 1, 'Pension Assets',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 2, 'Pension Fund Assets', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 3, 'Pension Pool',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 4, 'ERISA Assets',        'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 1, 'Pension Funded %',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 2, 'Pension Funding Ratio', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 3, 'Funded Status',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 4, 'Pension Funded Ratio',  'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Advance Rate'), 1, 'Advance Rate',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Advance Rate'), 2, 'Agent Advance Rate', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Advance Rate'), 3, 'Adv. Rate',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Advance Rate'), 4, 'Rate',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Advance Rate'), 5, 'Applicable Rate',    'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Concentration Limit'), 1, 'Concentration Limit',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Concentration Limit'), 2, 'Agent Concentration Limit','Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Concentration Limit'), 3, 'Conc. Limit',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Concentration Limit'), 4, 'Excel Concentration',     'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Concentration Limit'), 5, 'Max Concentration',       'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Borrowing Base'), 1, 'Agent Borrowing Base', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Borrowing Base'), 2, 'Agent BB',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Borrowing Base'), 3, 'Facility BB',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Borrowing Base'), 4, 'Agent Base',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Agent Borrowing Base'), 5, 'BB Amount',            'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 1, 'S&P',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 2, 'S&P Rating',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 3, 'S&P Credit Rating','Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 4, 'S and P',          'Core', NULL),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 1, 'Moody''s',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 2, 'Moody''s Rating',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 3, 'Moodys',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 4, 'Applicable Rating', 'Bank', 'BNY'),

  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 1, 'Fitch',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 2, 'Fitch Rating',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 3, 'Fitch Credit Rating','Core', NULL);

-- ── Field Mapping Dictionary: blocklist ──────────────────────────────────────

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
