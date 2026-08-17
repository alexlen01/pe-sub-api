-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  Consolidated seed — all reference data in final form.                 ║
-- ║  38 canonical fields across 9 groups; all aliases, blocklist, and      ║
-- ║  suggestions reflect the accumulated state of V1_14 through V1_26.    ║
-- ║  BB template rows live in the bb_template_* seed blocks below.          ║
-- ╚══════════════════════════════════════════════════════════════════════════╝

-- ── Config ────────────────────────────────────────────────────────────────────

INSERT INTO config (key, value) VALUES ('busa_tiers', '[
  {"cls":"Rated Investor",             "rate":90},
  {"cls":"Unrated NAV > $1Bn",         "rate":75},
  {"cls":"FoF & Other > $10Bn AUM",    "rate":75},
  {"cls":"Corp Pension > $5Bn Assets", "rate":65},
  {"cls":"Other Institutional",        "rate":50},
  {"cls":"Excluded",                   "rate":0}
]');

INSERT INTO config (key, value) VALUES ('agent_tiers', '[
  {"cls":"Rated Included",           "rate":90},
  {"cls":"Non-Rated Included",       "rate":75},
  {"cls":"Designated Institutional", "rate":60},
  {"cls":"Designated PWM",           "rate":50},
  {"cls":"Ineligible Investor",      "rate":0}
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
-- 38 fields across 9 groups.
-- is_derived = TRUE: agent-calculated output; display / cross-check only.

INSERT INTO fm_canonical_fields
    (group_name, group_sort, field_sort, canonical, lp_master_field, disambiguation, extraction_key, is_derived)
VALUES

  -- ── Group 1: Identity & Core Hierarchy ──────────────────────────────────────
  ('Identity & Core Hierarchy', 1, 1,
      'Investor Name',
      'Identity & Core Hierarchy - Investor Name',
      NULL,
      'INVESTOR_NAME', FALSE),

  ('Identity & Core Hierarchy', 1, 2,
      'Parent / Sponsor',
      'Identity & Core Hierarchy - Parent / Sponsor',
      'Ultimate parent or sponsoring entity of the LP',
      NULL, FALSE),

  ('Identity & Core Hierarchy', 1, 3,
      'SPV Flag',
      'Identity & Core Hierarchy - SPV Flag',
      'Indicates whether the LP is a Special Purpose Vehicle / entity. '
      'SPVs are typically excluded from concentration limits or treated distinctly under credit agreements. '
      'Captured as a boolean-style flag (Y/N, Yes/No, TRUE/FALSE, X) from agent submissions.',
      'SPV_FLAG', FALSE),

  ('Identity & Core Hierarchy', 1, 4,
      'Transferee',
      'Identity & Core Hierarchy - Transferee',
      'Y where LP received a transferred commitment; blank otherwise',
      NULL, FALSE),

  ('Identity & Core Hierarchy', 1, 5,
      'Region / Location',
      'Identity & Core Hierarchy - Region / Location',
      'Geographic region or domicile of the LP. '
      'Typical values: US, Europe, Asia-Pacific, Latin America, Middle East, Offshore. '
      'May appear as country, region code, or descriptive label in agent borrowing base submissions.',
      'REGION_LOCATION', FALSE),

  -- ── Group 2: Classification & Underwriting Data ──────────────────────────────
  ('Classification & Underwriting Data', 2, 1,
      'Investor Type',
      'Classification & Underwriting Data - Investor Type',
      'The legal persona of the entity — what the LP fundamentally is. '
      'Examples: Pension Fund, Endowment, Sovereign Wealth Fund, High Net Worth Individual, '
      'Insurance Company. Captured verbatim from the agent document; do not map to the UBS credit category.',
      'INVESTOR_TYPE', FALSE),

  ('Classification & Underwriting Data', 2, 2,
      'Institutional vs HNW',
      'Classification & Underwriting Data - Institutional vs HNW',
      'Broad UBS-internal segmentation of the LP as either an Institutional investor or a '
      'High Net Worth (HNW / PWM) individual / family office. '
      'Drives advance-rate and concentration treatment under UBS credit policy.',
      'INSTITUTIONAL_HNW', FALSE),

  ('Classification & Underwriting Data', 2, 3,
      'LP Category',
      'Classification & Underwriting Data - LP Category',
      'The credit-agreement classification of the LP — how the facility treats the entity for '
      'concentration limits and advance rates. Also called Agent LP Classification. May appear as a '
      'column OR as group-header rows filled down onto every LP beneath (e.g. "Rated Included", '
      '"Designated PWM"). Standard values: Rated Included, Non-Rated Included, Designated Institutional, '
      'Designated PWM, Largest 5 Designated, Aggregate Designated PWM. Do not normalise to the UBS tier.',
      'AGENT_LP_CLASSIFICATION', FALSE),

  ('Classification & Underwriting Data', 2, 4,
      'UBS (Internal) LP Classification',
      'Classification & Underwriting Data - UBS (Internal) LP Classification',
      'UBS proprietary credit-category assigned to the LP, distinct from the Agent LP Classification. '
      'Represents how UBS internally risk-rates or tiers the LP for underwriting purposes. '
      'Do not conflate with the agent-supplied Borrowing Base category.',
      'UBS_LP_CLASSIFICATION', FALSE),

  ('Classification & Underwriting Data', 2, 5,
      'Eligibility Flag',
      'Classification & Underwriting Data - Eligibility Flag',
      'Y/Eligible/Included vs N/Excluded; agent-assigned per-LP; treat as derived — WF template encodes this as a formula column separate from Investor Category',
      'ELIGIBILITY_FLAG', TRUE),

  ('Classification & Underwriting Data', 2, 6,
      'Investment Grade Flag',
      'Classification & Underwriting Data - Investment Grade Flag',
      'Indicates whether the LP is classified as Investment Grade under the applicable credit agreement. '
      'Typically determined by credit-rating thresholds (e.g. BBB- / Baa3 or better). '
      'May appear as a boolean flag or as a descriptive label (Rated, IG, Non-IG).',
      'INVESTMENT_GRADE_FLAG', FALSE),

  -- ── Group 3: Credit Ratings ──────────────────────────────────────────────────
  ('Credit Ratings', 3, 1,
      'S&P Rating',
      'Credit Ratings - S&P Rating',
      'Use last occurrence when column header repeats in same sheet',
      NULL, FALSE),

  ('Credit Ratings', 3, 2,
      'Moody''s Rating',
      'Credit Ratings - Moody''s Rating',
      'Use last occurrence when column header repeats in same sheet',
      NULL, FALSE),

  ('Credit Ratings', 3, 3,
      'Fitch Rating',
      'Credit Ratings - Fitch Rating',
      NULL,
      NULL, FALSE),

  -- ── Group 4: Commitment Data ──────────────────────────────────────────────────
  ('Commitment Data', 4, 1,
      'Capital Commitments',
      'Commitment Data - Capital Commitments',
      'Prefer "Individual" prefix column over aggregate when both present',
      'COMMITMENT', FALSE),

  ('Commitment Data', 4, 2,
      '% of Capital Commitments',
      'Commitment Data - % of Capital Commitments',
      'LP''s commitment as a percentage of total fund commitments',
      NULL, FALSE),

  ('Commitment Data', 4, 3,
      'Called Capital',
      'Commitment Data - Called Capital',
      'Cumulative capital drawn from the LP to date',
      NULL, FALSE),

  ('Commitment Data', 4, 4,
      'Recallable Distributions',
      'Commitment Data - Recallable Distributions',
      'Prior distributions subject to recall; SVB template feature; added back to callable capital base when computing remaining callable capital',
      'RECALLABLE_DIST', FALSE),

  -- ── Group 5: Uncalled Data ────────────────────────────────────────────────────
  ('Uncalled Data', 5, 1,
      'Uncalled Capital',
      'Uncalled Data - Uncalled Capital',
      'Prefer "Individual" prefix; skip column if any qualifier blocklist term matches header',
      'UNCALLED', FALSE),

  ('Uncalled Data', 5, 2,
      '% of Uncalled Capital',
      'Uncalled Data - % of Uncalled Capital',
      'LP''s uncalled capital as a percentage of total fund uncalled',
      NULL, FALSE),

  ('Uncalled Data', 5, 3,
      '% of LP Called',
      'Uncalled Data - % of LP Called',
      'Percentage of the LP''s own commitment that has been drawn',
      NULL, FALSE),

  -- ── Group 6: Financial Scale Metrics ─────────────────────────────────────────
  ('Financial Scale Metrics', 6, 1,
      'Size Metric Type',
      'Financial Scale Metrics - Size Metric Type',
      'The metric used by the agent to classify LP size, such as AUM, NAV, net worth, commitments, or a named size category',
      NULL, FALSE),

  ('Financial Scale Metrics', 6, 2,
      'Size Value / Tier',
      'Financial Scale Metrics - Size Value / Tier',
      'The reported LP size value, bucket, or tier corresponding to the selected size metric type',
      NULL, FALSE),

  ('Financial Scale Metrics', 6, 3,
      'AUM',
      'Financial Scale Metrics - AUM',
      NULL,
      'AUM', FALSE),

  ('Financial Scale Metrics', 6, 4,
      'NAV',
      'Financial Scale Metrics - NAV',
      'Net asset value of the LP''s fund interest at most recent reporting date',
      NULL, FALSE),

  ('Financial Scale Metrics', 6, 5,
      'Net Worth',
      'Financial Scale Metrics - Net Worth',
      'Total net worth of the LP or investor entity, as reported by the agent',
      NULL, FALSE),

  ('Financial Scale Metrics', 6, 6,
      'Pension Assets',
      'Financial Scale Metrics - Pension Assets',
      'Total pension fund assets managed by or on behalf of the LP',
      NULL, FALSE),

  ('Financial Scale Metrics', 6, 7,
      'Pension Funded %',
      'Financial Scale Metrics - Pension Funded %',
      'Funded status of the LP''s pension plan expressed as a percentage',
      NULL, FALSE),

  -- ── Group 7: Borrowing Base ───────────────────────────────────────────────────
  -- Fields 1–4 are agent-calculated outputs captured for display and cross-check
  -- against the UBS engine.
  ('Borrowing Base', 7, 1,
      'Borrowing Base',
      'Borrowing Base - Borrowing Base',
      'LP-level borrowing base as reported by the facility agent (= Eligible Commitment × Advance Rate)',
      'BORROWING_BASE', TRUE),

  ('Borrowing Base', 7, 2,
      '% of Borrowing Base',
      'Borrowing Base - % of Borrowing Base',
      'LP BB contribution as % of total facility borrowing base; agent-calculated informational column',
      'PCT_OF_BORROWING_BASE', TRUE),

  ('Borrowing Base', 7, 3,
      'Eligible Commitment',
      'Borrowing Base - Eligible Commitment',
      'LP uncalled commitment after per-LP concentration haircut applied; agent-calculated; maps to "Eligible Commitment" (GS/WF) and "Remaining Callable Capital Adjusted for Concentration Limit" (SVB)',
      'ELIGIBLE_COMMITMENT', TRUE),

  ('Borrowing Base', 7, 4,
      '% of Eligible Uncalled',
      'Borrowing Base - % of Eligible Uncalled',
      'LP eligible uncalled as % of total eligible uncalled pool; agent-calculated; appears as "% Eligible Unfunded Commitment" in GS and WF templates',
      'PCT_OF_ELIGIBLE_UNCALLED', TRUE),

  -- ── Group 8: Advance Rates & Concentration Limits ────────────────────────────
  ('Advance Rates & Concentration Limits', 8, 1,
      'Advance Rate',
      'Advance Rates & Concentration Limits - Advance Rate',
      NULL,
      'ADVANCE_RATE', FALSE),

  ('Advance Rates & Concentration Limits', 8, 2,
      'Concentration Limit',
      'Advance Rates & Concentration Limits - Concentration Limit',
      NULL,
      'CONCENTRATION_LIMIT', FALSE),

  ('Advance Rates & Concentration Limits', 8, 3,
      'Concentration (%)',
      'Advance Rates & Concentration Limits - Concentration (%)',
      'LP''s concentration expressed as a percentage of the relevant base, as reported by the agent.',
      NULL, FALSE),

  ('Advance Rates & Concentration Limits', 8, 4,
      'Excess Concentration',
      'Advance Rates & Concentration Limits - Excess Concentration',
      'Dollar amount by which LP uncalled exceeds the per-LP concentration cap; agent-calculated as max(0, uncalled − cap × total_eligible)',
      NULL, TRUE),

  ('Advance Rates & Concentration Limits', 8, 5,
      'Excess Concentration (%)',
      'Advance Rates & Concentration Limits - Excess Concentration (%)',
      'Excess concentration expressed as a percentage; agent-calculated overage relative to the per-LP concentration cap.',
      NULL, TRUE),

  -- ── Group 9: Notes ────────────────────────────────────────────────────────────
  ('Notes', 9, 1,
      'Notes',
      'Notes - Notes',
      'Free-text analyst notes captured from agent borrowing base submissions',
      'NOTES', FALSE);

-- ── Field Mapping Dictionary: aliases ────────────────────────────────────────

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank) VALUES

  -- ── Investor Name ─────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 1,  'Investor Name',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 2,  'Investor Name (Agent Records)',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 3,  'Investor',                        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 4,  'LP Name',                         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 5,  'Limited Partner',                 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 6,  'Fund Investor',                   'Bank', 'SVB'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Name'), 7,  'Deal Investor Name',              'Bank', 'WF'),

  -- ── Parent / Sponsor ──────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 1, 'Parent / Sponsor',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 2, 'Parent',                     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 3, 'Sponsor',                    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 4, 'Parent Entity',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 5, 'Sponsoring Entity',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 6, 'Ultimate Parent',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 7, 'Parent Organization',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 8, 'Parent / Sponsor / Manager', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Parent / Sponsor'), 9, 'Manager',                    'Core', NULL),

  -- ── SPV Flag ──────────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'SPV Flag'), 1, 'SPV Flag',                'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'SPV Flag'), 2, 'SPV',                     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'SPV Flag'), 3, 'Special Purpose Vehicle', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'SPV Flag'), 4, 'SPV Indicator',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'SPV Flag'), 5, 'Entity SPV Flag',         'Core', NULL),

  -- ── Transferee ────────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 1, 'Transferee',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 2, 'Transfer Flag',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 3, 'Assignee',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 4, 'Assignment Flag', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 5, 'Transferred LP',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Transferee'), 6, 'Transferred From',  'Bank', 'SVB'),

  -- ── Region / Location ─────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Region / Location'), 1, 'Region / Location', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Region / Location'), 2, 'Region',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Region / Location'), 3, 'Location',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Region / Location'), 4, 'Geography',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Region / Location'), 5, 'Domicile',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Region / Location'), 6, 'Country',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Region / Location'), 7, 'Jurisdiction',      'Core', NULL),

  -- ── Investor Type ─────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 1, 'Investor Type',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 2, 'LP Type Classification',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 3, 'Investor Classification', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 4, 'Category of Investor',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investor Type'), 5, 'Entity Type',             'Bank', 'BNY'),

  -- ── Institutional vs HNW ─────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Institutional vs HNW'), 1, 'Institutional vs HNW',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Institutional vs HNW'), 2, 'Institutional / HNW',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Institutional vs HNW'), 3, 'HNW Flag',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Institutional vs HNW'), 4, 'PWM Flag',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Institutional vs HNW'), 5, 'Investor Segment',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Institutional vs HNW'), 6, 'Client Segment',        'Core', NULL),

  -- ── LP Category ───────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 1, 'LP Category',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 2, 'Agent LP Classification', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 3, 'LP Classification',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 4, 'Borrowing Base Category', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 5, 'Credit Category',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 6, 'Eligibility Tier',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 7, 'Investor Tier',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 8, 'Investor Status',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'LP Category'), 9, 'LP Status',               'Core', NULL),

  -- ── UBS (Internal) LP Classification ─────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'UBS (Internal) LP Classification'), 1, 'UBS (Internal) LP Classification', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'UBS (Internal) LP Classification'), 2, 'UBS LP Classification',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'UBS (Internal) LP Classification'), 3, 'UBS Classification',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'UBS (Internal) LP Classification'), 4, 'Internal LP Category',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'UBS (Internal) LP Classification'), 5, 'UBS Credit Category',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'UBS (Internal) LP Classification'), 6, 'UBS Tier',                         'Core', NULL),

  -- ── Eligibility Flag ──────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligibility Flag'), 1, 'Eligibility', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligibility Flag'), 2, 'Eligible',    'Bank', 'WF'),

  -- ── Investment Grade Flag ─────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investment Grade Flag'), 1, 'Investment Grade Flag', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investment Grade Flag'), 2, 'Investment Grade',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investment Grade Flag'), 3, 'IG Flag',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investment Grade Flag'), 4, 'IG Indicator',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investment Grade Flag'), 5, 'Rated Flag',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Investment Grade Flag'), 6, 'Credit Quality Flag',   'Core', NULL),

  -- ── S&P Rating ────────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 1, 'S&P',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 2, 'S&P Rating',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 3, 'S&P Credit Rating', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 4, 'S and P',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 5, 'S & P''s Rating',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 6, 'S & P',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'S&P Rating'), 7, 'Investor S&P',      'Bank', 'WF'),

  -- ── Moody's Rating ────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 1, 'Moody''s',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 2, 'Moody''s Rating',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 3, 'Moodys',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 4, 'Applicable Rating', 'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Moody''s Rating'), 5, 'Investor Moody',    'Bank', 'WF'),

  -- ── Fitch Rating ──────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 1, 'Fitch',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 2, 'Fitch Rating',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Fitch Rating'), 3, 'Fitch Credit Rating', 'Core', NULL),

  -- ── Capital Commitments ───────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 1, 'Capital Commitments',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 2, 'Committed Capital',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 3, 'Original Commitment',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 4, 'Individual Original Commitment',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 5, 'Total Commitment',                'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 6, 'Commitment (USD)',                 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'), 7, 'Total Capital Commitments ($)',    'Core', NULL),

  -- ── % of Capital Commitments ──────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 1, '% of Capital Commitments', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 2, '% Commitment',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 3, 'Commitment Percentage',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Capital Commitments'), 4, 'LP Commitment %',          'Core', NULL),

  -- ── Called Capital ────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 1, 'Called Capital',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 2, 'Drawn Capital',     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 3, 'Funded Capital',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 4, 'Capital Drawn',     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 5, 'Funded Commitment', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Called Capital'), 6, 'Called Commitment', 'Core', NULL),

  -- ── Recallable Distributions ──────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Recallable Distributions'), 1, 'Recallable Distributions', 'Bank', 'SVB'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Recallable Distributions'), 2, 'Recallable Capital',       'Bank', 'SVB'),

  -- ── Uncalled Capital ──────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 1, 'Uncalled Capital',                 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 2, 'Unfunded Capital Commitment',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 3, 'Individual Unfunded Commitment',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 4, 'Unfunded Commitment',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 5, 'Remaining Callable Capital',       'Bank', 'SVB'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 6, 'Remaining Commitment',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 7, 'Uncalled Capital (USD)',            'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Uncalled Capital'), 8, 'Unfunded Capital Commitments ($)', 'Core', NULL),

  -- ── % of Uncalled Capital ─────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 1, '% of Uncalled Capital',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 2, '% Uncalled',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 3, 'Uncalled %',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 4, '% Unfunded',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 5, 'Uncalled Ratio',              'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 6, '% Total Unfunded Commitment', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Uncalled Capital'), 7, '% of Unfunded Commitment',    'Bank', 'WF'),

  -- ── % of LP Called ────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 1, '% of LP Called', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 2, '% Called',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 3, 'Called Ratio',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 4, 'Draw Percentage', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of LP Called'), 5, '% Funded',        'Core', NULL),

  -- ── Size Metric Type ──────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'), 1, 'Size Metric Type',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'), 2, 'LP Size Metric',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'), 3, 'Size Metric',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'), 4, 'Scale Metric',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'), 5, 'Financial Size Metric', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'), 6, 'Metric Type',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'), 7, 'LP Size Criteria',      'Core', NULL),

  -- ── Size Value / Tier ─────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'), 1, 'Size Value / Tier',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'), 2, 'Size Tier',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'), 3, 'LP Size Tier',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'), 4, 'Size Value',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'), 5, 'Financial Size Tier', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'), 6, 'Investor Size Tier',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'), 7, 'Size Bucket',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'), 8, 'LP Size ($ Bil)',     'Core', NULL),

  -- ── AUM ───────────────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 1, 'AUM',                     'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 2, 'Assets Under Management', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 3, 'Net Assets',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 4, 'Net Assets (range)',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'AUM'), 5, 'Total AUM',                'Core', NULL),

  -- ── NAV ───────────────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 1, 'NAV',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 2, 'Net Asset Value',  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 3, 'Fund NAV',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 4, 'Total NAV',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'NAV'), 5, 'NAV Range (USD)',   'Core', NULL),

  -- ── Net Worth ─────────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Net Worth'), 1, 'Net Worth',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Net Worth'), 2, 'Total Net Worth',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Net Worth'), 3, 'Investor Net Worth', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Net Worth'), 4, 'Networth',           'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Net Worth'), 5, 'Net Worth (USD)',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Net Worth'), 6, 'Net Worth Range',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Net Worth'), 7, 'NW',                 'Core', NULL),

  -- ── Pension Assets ────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 1, 'Pension Assets',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 2, 'Pension Fund Assets', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 3, 'Pension Pool',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Assets'), 4, 'ERISA Assets',        'Core', NULL),

  -- ── Pension Funded % ──────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 1, 'Pension Funded %',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 2, 'Pension Funding Ratio', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 3, 'Funded Status',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Pension Funded %'), 4, 'Pension Funded Ratio',  'Core', NULL),

  -- ── Borrowing Base ────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 1, 'Borrowing Base',             'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 2, 'Agent Borrowing Base',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 3, 'Agent BB',                   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 4, 'Facility BB',                'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 5, 'Agent Base',                 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 6, 'BB Amount',                  'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Borrowing Base'), 7, 'Borrowing Base Contribution','Core', NULL),

  -- ── % of Borrowing Base ───────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Borrowing Base'), 1, '% of Borrowing Base', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Borrowing Base'), 2, '% BB',                'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Borrowing Base'), 3, 'BB Percentage',       'Core', NULL),

  -- ── Eligible Commitment ───────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligible Commitment'), 1, 'Eligible Commitment',                                        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligible Commitment'), 2, 'Remaining Callable Capital Adjusted for Concentration Limit', 'Bank', 'SVB'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Eligible Commitment'), 3, 'Eligible Uncalled',                                           'Core', NULL),

  -- ── % of Eligible Uncalled ────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Eligible Uncalled'), 1, '% Eligible Unfunded Commitment', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Eligible Uncalled'), 2, '% of Eligible Uncalled Capital', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Eligible Uncalled'), 3, '% Eligible Uncalled',            'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = '% of Eligible Uncalled'), 4, '% of Eligible Unfunded Commitment', 'Bank', 'WF'),

  -- ── Advance Rate ──────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 1, 'Advance Rate',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 2, 'Agent Advance Rate', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 3, 'Adv. Rate',          'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 4, 'Rate',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 5, 'Applicable Rate',    'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Advance Rate'), 6, 'Advance Rate (%)',   'Core', NULL),

  -- ── Concentration Limit ───────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 1, 'Concentration Limit',       'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 2, 'Agent Concentration Limit', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 3, 'Conc. Limit',               'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 4, 'Excel Concentration',       'Bank', 'BNY'),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 5, 'Max Concentration',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration Limit'), 6, 'Aggregate Concentration',   'Core', NULL),

  -- ── Concentration (%) ─────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration (%)'), 1, 'Concentration (%)', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration (%)'), 2, 'Concentration %',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Concentration (%)'), 3, 'LP Concentration',  'Core', NULL),

  -- ── Excess Concentration ──────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration'), 1, 'Excess Concentration', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration'), 2, 'Conc. Overage',        'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration'), 3, 'Concentration Excess', 'Core', NULL),

  -- ── Excess Concentration (%) ──────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration (%)'), 1, 'Excess Concentration (%)', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration (%)'), 2, 'Excess Concentration %',   'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Excess Concentration (%)'), 3, '% Excess Concentration',   'Core', NULL),

  -- ── Notes ─────────────────────────────────────────────────────────────────────
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Notes'), 1, 'Notes',         'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Notes'), 2, 'Analyst Notes', 'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Notes'), 3, 'LP Notes',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Notes'), 4, 'Comments',      'Core', NULL),
  ((SELECT id FROM fm_canonical_fields WHERE canonical = 'Notes'), 5, 'Remarks',       'Core', NULL);

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

-- ── LP Rates: simulated feed — effective 2025-01-01 ───────────────────────────
-- Back-dated to Jan 2025 so findLatestAsOf(asOf) returns these rows for any
-- test submission with period >= 2025-01. In production populated by BATCH_FEED.
-- source = 'SIMULATED' marks these rows. No-op until lp_records are populated.
--
-- ubs_adv_rate_pct  : decimal fraction (0.9000 = 90%)
-- ubs_conc_limit_pct: per-LP cap as fraction of total eligible uncalled

INSERT INTO lp_rates (
    lp_id, effective_date, classification,
    ubs_adv_rate_pct, ubs_conc_limit_pct, source
)
SELECT
    id,
    '2025-01-01'::DATE,
    ubs_lp_category,
    CASE ubs_lp_category
        WHEN 'Rated'          THEN 0.9000
        WHEN 'Unrated >2bn'   THEN 0.7500
        WHEN 'Unrated 1–2bn'  THEN 0.6500
        WHEN 'Eligible'       THEN 0.5000
        WHEN 'Excluded'       THEN 0.0000
        ELSE                       0.5000
    END,
    CASE ubs_lp_category
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
