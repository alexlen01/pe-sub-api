-- Database-owned UI/domain configuration.
-- Existing V1_2 keys stay in place; this migration adds the missing config
-- entries used by the UI and patches matching_config with regex expansions.

-- Borrowing Base Criteria matrix (Concentration_Limits.xlsx tabs 2-3). Advance rate splits on
-- the LP's own funded % (pctCalled) at fundedThresholdPct; concentration limit is funded-
-- independent. Rated Investors resolve their band via the tri-party "eligible rating" waterfall
-- over S&P/Moody's/Fitch (three -> middle/median, two -> lower, one -> as-is); sub-BBB- clamps
-- to the BBB row. `label` carries the workbook's verbatim agency rating range.
INSERT INTO config (key, value) VALUES ('bb_criteria_matrix', $json$
{
  "fundedThresholdPct": 40,
  "ratingBands": {
    "AAA": { "sp": ["AAA"],               "moodys": ["Aaa"],               "fitch": ["AAA"] },
    "AA":  { "sp": ["AA+","AA","AA-"],     "moodys": ["Aa1","Aa2","Aa3"],   "fitch": ["AA+","AA","AA-"] },
    "A":   { "sp": ["A+","A","A-"],        "moodys": ["A1","A2","A3"],      "fitch": ["A+","A","A-"] },
    "BBB": { "sp": ["BBB+","BBB","BBB-"],  "moodys": ["Baa1","Baa2","Baa3"], "fitch": ["BBB+","BBB","BBB-"] }
  },
  "ratingBandSelection": "middle",
  "ratingBandTieBreak": { "three": "middle", "two": "lower", "one": "asIs" },
  "subInvestmentGradeBand": "BBB",
  "rated": [
    { "band": "AAA", "label": "AAA / Aaa",                  "concLimitPct": 25, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
    { "band": "AA",  "label": "AA+ / Aa1 to AA- / Aa3",     "concLimitPct": 20, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
    { "band": "A",   "label": "A+ / A1 to A- / A3",         "concLimitPct": 15, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
    { "band": "BBB", "label": "BBB+ / Baa1 to BBB- / Baa3", "concLimitPct": 10, "advanceRatePct": { "lt40": 65, "gte40": 90 } }
  ],
  "classes": [
    { "cls": "Corp Pension > $5Bn Assets", "category": "Unrated",    "concLimitPct": 25, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
    { "cls": "Corp Pension > $1Bn Assets", "category": "Unrated",    "concLimitPct": 20, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
    { "cls": "Unrated NAV > $1Bn",         "category": "Unrated",    "concLimitPct": 15, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
    { "cls": "FoF & Other > $10Bn AUM",    "category": "Designated", "concLimitPct": 10, "advanceRatePct": { "lt40": 65, "gte40": 75 } },
    { "cls": "Other Institutional",        "category": "Designated", "concLimitPct": 5,  "advanceRatePct": { "lt40": 50, "gte40": 65 } },
    { "cls": "HNW Feeder (acceptable)",    "category": "Designated", "concLimitPct": 5,  "advanceRatePct": { "lt40": 50, "gte40": 65 } },
    { "cls": "HNW (acceptable)",           "category": "Designated", "concLimitPct": 1,  "advanceRatePct": { "lt40": 0,  "gte40": 50 } },
    { "cls": "Excluded",                   "category": "Excluded",   "concLimitPct": 0,  "advanceRatePct": { "lt40": 0,  "gte40": 0 } }
  ]
}
$json$::jsonb)
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;

-- busa_tiers: extend the V1_2 seed (6 classes) to the 9-class taxonomy. DO UPDATE overrides
-- the value already inserted by V1_2. Existing classes' rates carried forward verbatim.
INSERT INTO config (key, value) VALUES ('busa_tiers', $json$
[
  {"cls":"Rated Investor",             "rate":90},
  {"cls":"Corp Pension > $5Bn Assets", "rate":65},
  {"cls":"Corp Pension > $1Bn Assets", "rate":90},
  {"cls":"Unrated NAV > $1Bn",         "rate":75},
  {"cls":"FoF & Other > $10Bn AUM",    "rate":75},
  {"cls":"Other Institutional",        "rate":50},
  {"cls":"HNW Feeder (acceptable)",    "rate":65},
  {"cls":"HNW (acceptable)",           "rate":50},
  {"cls":"Excluded",                   "rate":0}
]
$json$::jsonb)
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;

-- 9-class UBS taxonomy (tab 3). New: Corp Pension > $1Bn Assets, HNW Feeder (acceptable),
-- HNW (acceptable). Agent Designated PWM -> HNW Feeder (BB_CRITERIA_DESIGN.md Q5). Existing
-- six classes' rates/limits unchanged.
INSERT INTO config (key, value) VALUES ('classification_config', $json$
{
  "CLS_OPTS": ["", "Rated Investor", "Corp Pension > $5Bn Assets", "Corp Pension > $1Bn Assets", "Unrated NAV > $1Bn", "FoF & Other > $10Bn AUM", "Other Institutional", "HNW Feeder (acceptable)", "HNW (acceptable)", "Excluded"],
  "AGENT_CLS_OPTS": ["", "Rated Included", "Non-Rated Included", "Designated Institutional", "Designated PWM", "Ineligible Investor"],
  "UBS_CLS_OPTS": ["", "Rated Investor", "Corp Pension > $5Bn Assets", "Corp Pension > $1Bn Assets", "Unrated NAV > $1Bn", "FoF & Other > $10Bn AUM", "Other Institutional", "HNW Feeder (acceptable)", "HNW (acceptable)", "Excluded"],
  "UBS_CLS_DEFAULT_RATE": {
    "Rated Investor": "90%",
    "Corp Pension > $5Bn Assets": "65%",
    "Corp Pension > $1Bn Assets": "90%",
    "Unrated NAV > $1Bn": "75%",
    "FoF & Other > $10Bn AUM": "75%",
    "Other Institutional": "50%",
    "HNW Feeder (acceptable)": "65%",
    "HNW (acceptable)": "50%",
    "Excluded": "0%"
  },
  "AGENT_RATE_UBS_TIERS": [
    { "min": 90, "cls": "Rated Investor" },
    { "min": 75, "cls": "Unrated NAV > $1Bn" },
    { "min": 65, "cls": "Corp Pension > $5Bn Assets" },
    { "min": 50, "cls": "Other Institutional" },
    { "min": 0, "cls": "Excluded" }
  ],
  "AGENT_CLS_UBS_MAP": {
    "Rated Included": "Rated Investor",
    "Non-Rated Included": "Unrated NAV > $1Bn",
    "Designated Institutional": "Corp Pension > $5Bn Assets",
    "Designated PWM": "HNW Feeder (acceptable)",
    "Excluded Investor": "Excluded",
    "Ineligible Investors": "Excluded"
  },
  "LP_SIZE_CRITERIA_OPTS": ["", "AUM", "NAV", "Assets"],
  "REGION_OPTS": ["North America", "Europe", "Asia-Pacific", "Middle East", "Other"],
  "TYPE_OPTS": ["Institutional", "HNW"],
  "SP_RATING_OPTS": ["", "AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB+", "BBB", "BBB-", "BB+", "BB", "BB-", "B+", "B", "B-"],
  "MDY_RATING_OPTS": ["", "Aaa", "Aa1", "Aa2", "Aa3", "A1", "A2", "A3", "Baa1", "Baa2", "Baa3", "Ba1", "Ba2", "Ba3", "B1", "B2", "B3"],
  "FITCH_RATING_OPTS": ["", "AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB+", "BBB", "BBB-", "BB+", "BB", "BB-", "B+", "B", "B-"],
  "BUSA_RATE_MAP": {
    "Rated Investor": "90%",
    "Corp Pension > $5Bn Assets": "65%",
    "Corp Pension > $1Bn Assets": "90%",
    "Unrated NAV > $1Bn": "75%",
    "FoF & Other > $10Bn AUM": "75%",
    "Other Institutional": "50%",
    "HNW Feeder (acceptable)": "65%",
    "HNW (acceptable)": "50%",
    "Excluded": "0%"
  },
  "AGENT_RATE_MAP": {
    "Rated Included": "90%",
    "Non-Rated Included": "75%",
    "Designated Institutional": "60%",
    "Designated PWM": "50%",
    "Excluded Investor": "0%"
  },
  "CLS_TAG_MAP": {
    "Rated Investor": "tag-rated",
    "Corp Pension > $5Bn Assets": "tag-un1",
    "Corp Pension > $1Bn Assets": "tag-un1",
    "Unrated NAV > $1Bn": "tag-un2",
    "FoF & Other > $10Bn AUM": "tag-un2",
    "Other Institutional": "tag-elig",
    "HNW Feeder (acceptable)": "tag-elig",
    "HNW (acceptable)": "tag-elig",
    "Excluded": "tag-excl"
  },
  "CLS_CRITERIA": {
    "Rated Investor": "Rated investor tier",
    "Corp Pension > $5Bn Assets": "Corporate pension with assets greater than USD 5bn",
    "Corp Pension > $1Bn Assets": "Corporate pension with total assets between USD 1bn and USD 5bn",
    "Unrated NAV > $1Bn": "Unrated investor with NAV greater than USD 1bn",
    "FoF & Other > $10Bn AUM": "Fund of funds or other investor with AUM greater than USD 10bn",
    "Other Institutional": "Other institutional investor",
    "HNW Feeder (acceptable)": "High net worth feeder meeting acceptable criteria",
    "HNW (acceptable)": "Acceptable family office or high net worth investor",
    "Excluded": "Fails eligibility (jurisdiction, ERISA, concentration limit, etc.)"
  },
  "INVESTOR_TYPE_OPTS": ["", "Pension Fund", "Endowment", "Foundation", "Family Office", "Fund of Funds", "Sovereign Wealth Fund", "Insurance Company", "Healthcare", "Corporate", "Public Pension", "Investment Consultant", "Institutional Investor", "Hedge Fund", "Other Institutional", "HNW"],
  "LP_CATEGORY_LABEL": {
    "Rated Investor": "Rated Investors",
    "Corp Pension > $5Bn Assets": "Unrated Investors",
    "Corp Pension > $1Bn Assets": "Unrated Investors",
    "Unrated NAV > $1Bn": "Unrated Investors",
    "FoF & Other > $10Bn AUM": "Unrated Investors",
    "Other Institutional": "Included Investors",
    "HNW Feeder (acceptable)": "Included Investors",
    "HNW (acceptable)": "Included Investors",
    "Excluded": "Excluded Investors"
  }
}
$json$::jsonb)
ON CONFLICT (key) DO NOTHING;

-- Per-LP concentration-limit defaults (percent of total uncalled capital), keyed by
-- LP classification. Sourced from pe-sub-docs/Concentration_Limits.xls, which specifies
-- each class as a range; the stored default is the UPPER bound of that range (maximum
-- headroom). Excluded is a hard 0 — an excluded LP contributes nothing to the base.
INSERT INTO config (key, value) VALUES ('cls_conc_limit_defaults', $json$
{
  "Rated Investor": 20.0,
  "Corp Pension > $5Bn Assets": 12.5,
  "Corp Pension > $1Bn Assets": 20.0,
  "Unrated NAV > $1Bn": 15.0,
  "FoF & Other > $10Bn AUM": 10.0,
  "Other Institutional": 7.5,
  "HNW Feeder (acceptable)": 5.0,
  "HNW (acceptable)": 1.0,
  "Excluded": 0.0
}
$json$::jsonb)
ON CONFLICT (key) DO NOTHING;

-- Accepted range (min/max percent) per LP classification, from the same source workbook.
-- Consumed by the LP record entry form to warn — without blocking — when an analyst enters
-- a concentration limit outside the norm for the selected class. Excluded is pinned to 0.
INSERT INTO config (key, value) VALUES ('cls_conc_limit_bounds', $json$
{
  "Rated Investor": { "min": 15.0, "max": 20.0 },
  "Corp Pension > $5Bn Assets": { "min": 10.0, "max": 12.5 },
  "Corp Pension > $1Bn Assets": { "min": 20.0, "max": 20.0 },
  "Unrated NAV > $1Bn": { "min": 10.0, "max": 15.0 },
  "FoF & Other > $10Bn AUM": { "min": 7.5, "max": 10.0 },
  "Other Institutional": { "min": 5.0, "max": 7.5 },
  "HNW Feeder (acceptable)": { "min": 5.0, "max": 5.0 },
  "HNW (acceptable)": { "min": 1.0, "max": 1.0 },
  "Excluded": { "min": 0.0, "max": 0.0 }
}
$json$::jsonb)
ON CONFLICT (key) DO NOTHING;

INSERT INTO config (key, value) VALUES ('wizard_config', $json$
{
  "WIZARD_STEPS": ["Select Facility", "Upload Document", "Review Extraction", "Review Matches", "LP Category & Rate Assignment"],
  "SNAPSHOT_OPTIONS": ["May 2026 (latest)", "April 2026", "March 2026"],
  "CALC_MODES": [
    { "id": "full", "title": "Full Recalculation", "desc": "Recompute all eligibility rules, advance rates, and LP classifications from scratch against the uploaded Agent BB.", "recommended": true },
    { "id": "incremental", "title": "Incremental Update", "desc": "Apply only the delta from newly matched and accepted LPs. Existing Shadow BB lines remain unchanged.", "recommended": false },
    { "id": "preview", "title": "Preview Only", "desc": "Calculate and display the Shadow BB without committing results. Useful for scenario analysis before finalising.", "recommended": false }
  ]
}
$json$::jsonb)
ON CONFLICT (key) DO NOTHING;

INSERT INTO config (key, value) VALUES ('audit_config', $json$
{
  "EVENT_TYPES": ["", "LP Reclassified", "BB Recalculated", "Upload", "Export", "Config Change", "Login"],
  "EVENT_TYPE_VARIANT": {
    "LP Reclassified": "amber",
    "BB Recalculated": "active",
    "Upload": "active",
    "Export": "",
    "Config Change": "amber",
    "Login": ""
  },
  "AUDIT_RETENTION_LABEL": "7 years",
  "DEFAULT_DATE_FROM": "2026-05-01",
  "DEFAULT_DATE_TO": "2026-05-27"
}
$json$::jsonb)
ON CONFLICT (key) DO NOTHING;

INSERT INTO config (key, value) VALUES ('report_config', $json$
{
  "REPORT_TABS": [
    { "id": "collateral", "label": "Collateral & Coverage" },
    { "id": "ear", "label": "Effective Advance Rates" },
    { "id": "agent-bank", "label": "Agent Bank Exposure" },
    { "id": "concentration", "label": "Concentration Exposures" },
    { "id": "adhoc", "label": "Ad Hoc Reporting" },
    { "id": "scheduled", "label": "Scheduled Reports" }
  ],
  "CONCENTRATION_TESTS": [
    "Single-LP limit breach (>15% of UBS BB)",
    "Top-10 LP concentration breach (>60% of UBS BB)",
    "Unrated aggregate exposure (>50% of UBS BB)",
    "Non-US LP aggregate exposure (>30% of UBS BB)"
  ],
  "REPORT_SCHEDULES": [
    { "name": "Monthly Status Reset - All Active Facilities", "freq": "1st of month, 00:00", "next": "Jun 1" }
  ]
}
$json$::jsonb)
ON CONFLICT (key) DO NOTHING;

INSERT INTO config (key, value) VALUES ('matching_config', $json$
{
  "thresholds": {
    "autoAccept": 95,
    "reviewQueue": 80,
    "noMatch": 50,
    "jwWeight": 0.6,
    "levWeight": 0.4,
    "stripSuffixes": true,
    "caseFold": true,
    "punctuation": true,
    "abbrevExpand": true,
    "retirementNormalize": true
  },
  "legalSuffixes": [
    { "abbr": "LP", "full": "Limited Partnership", "strip": true },
    { "abbr": "LLC", "full": "Limited Liability Company", "strip": true },
    { "abbr": "Ltd", "full": "Limited", "strip": true },
    { "abbr": "Pte.", "full": "Private (Singapore)", "strip": true },
    { "abbr": "LLP", "full": "Limited Liability Partnership", "strip": true },
    { "abbr": "GmbH", "full": "Gesellschaft mit beschrankter Haftung", "strip": true },
    { "abbr": "Mgmt", "full": "Management", "strip": false },
    { "abbr": "Inv.", "full": "Investments / Investors", "strip": false }
  ],
  "knownAbbreviations": [
    { "token": "GIC", "expansion": "Government Investment Corporation" },
    { "token": "ADIA", "expansion": "Abu Dhabi Investment Authority" },
    { "token": "CPPIB", "expansion": "Canada Pension Plan Investment Board" },
    { "token": "OTPP", "expansion": "Ontario Teachers Pension Plan" },
    { "token": "CalPERS", "expansion": "California Public Employees Retirement System" },
    { "token": "JPM", "expansion": "JPMorgan Chase" },
    { "token": "BofA", "expansion": "Bank of America" },
    { "token": "BAML", "expansion": "Bank of America Merrill Lynch" },
    { "token": "WF", "expansion": "Wells Fargo" },
    { "token": "PNC", "expansion": "PNC Bank" }
  ],
  "abbrevRegexMap": {
    "Inv\\.?": "Investment",
    "Mgmt": "Management",
    "Fam\\.?": "Family",
    "Auth\\.?": "Authority",
    "Co\\.?(?=\\s|$)": "Company",
    "Corp\\.?": "Corporation"
  }
}
$json$::jsonb)
ON CONFLICT (key) DO NOTHING;

-- Retire the "Included (PWM)" class (BB_CRITERIA_DESIGN.md Q5): any existing rows move to the
-- successor HNW Feeder class. No-op on a fresh database (the label only ever existed in the
-- prototype), kept so the migration is correct against any environment that adopted it.
UPDATE lp_records SET ubs_lp_category = 'HNW Feeder (acceptable)' WHERE ubs_lp_category = 'Included (PWM)';
UPDATE lp_rates   SET classification   = 'HNW Feeder (acceptable)' WHERE classification   = 'Included (PWM)';
UPDATE lp_master  SET ubs_lp_category = 'HNW Feeder (acceptable)' WHERE ubs_lp_category = 'Included (PWM)';
