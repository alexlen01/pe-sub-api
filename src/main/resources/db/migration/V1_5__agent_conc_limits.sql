-- Agent-side concentration limits become first-class configuration.
--
-- Until now the platform had a configured DEFAULT ADVANCE RATE per Agent LP Category (`agent_tiers`
-- / classification_config.AGENT_RATE_MAP) but no configured CONCENTRATION LIMIT for the same
-- categories. The UI filled that hole by routing an Agent LP Category through AGENT_CLS_UBS_MAP
-- into cls_conc_limit_defaults — i.e. it answered an agent-side question with a UBS-side number,
-- and the agent side could not be tuned without moving the UBS defaults underneath it.
--
-- Both defaults are now stated directly against the Agent LP Category:
--   * `agent_tiers` rows gain `concLimitPct` alongside `rate`  (the Configuration screen's editor)
--   * classification_config gains AGENT_CONC_LIMIT_MAP         (the lookup map, mirroring
--                                                               AGENT_RATE_MAP's shape)
--
-- Seeded values reproduce EXACTLY what the AGENT_CLS_UBS_MAP detour produced, so no LP's suggested
-- limit moves on this migration:
--   Rated Included           -> Rated Investor              -> 20
--   Non-Rated Included       -> Unrated NAV > $1Bn          -> 15
--   Designated Institutional -> Corp Pension > $5Bn Assets  -> 12.5
--   Designated PWM           -> HNW Feeder (acceptable)     -> 5
--   Ineligible Investor      -> Excluded                    -> 0   (hard zero: an ineligible LP
--                                                                   contributes nothing to the base)
-- Basis is percent of TOTAL UNCALLED CAPITAL, the same basis as cls_conc_limit_defaults.
-- Keep in step with pe-sub-jobs/data/reference/agent_rate_map.csv.

-- agent_tiers: DO UPDATE, because V1_2 already inserted the rate-only rows on every environment.
INSERT INTO config (key, value) VALUES ('agent_tiers', $json$
[
  {"cls":"Rated Included",           "rate":90, "concLimitPct":20},
  {"cls":"Non-Rated Included",       "rate":75, "concLimitPct":15},
  {"cls":"Designated Institutional", "rate":60, "concLimitPct":12.5},
  {"cls":"Designated PWM",           "rate":50, "concLimitPct":5},
  {"cls":"Excluded Investor",       "rate":0,  "concLimitPct":0}
]
$json$::jsonb)
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;

-- classification_config: MERGE rather than replace. V1_3 inserts it ON CONFLICT DO NOTHING, so an
-- environment that has since edited any other key of this document must keep those edits; `||`
-- adds AGENT_CONC_LIMIT_MAP and leaves every sibling key untouched. The guard makes the statement
-- a no-op once applied, so a manual re-run cannot clobber a locally tuned map. jsonb_exists() is
-- the function spelling of the `?` operator — spelled out because a bare `?` in a migration is
-- ambiguous with a JDBC bind placeholder.
--
-- Values are NUMBERS, not the "90%" strings AGENT_RATE_MAP uses. Every concentration limit in this
-- database is numeric (cls_conc_limit_defaults, cls_conc_limit_bounds, bb_criteria_matrix
-- .concLimitPct); the rate maps' percent-string shape is a legacy of the prototype's display code
-- and is not worth propagating into a new key.
UPDATE config
   SET value = value || $json$
{
  "AGENT_CONC_LIMIT_MAP": {
    "Rated Included": 20.0,
    "Non-Rated Included": 15.0,
    "Designated Institutional": 12.5,
    "Designated PWM": 5.0,
    "Excluded Investor": 0.0
  }
}
$json$::jsonb
 WHERE key = 'classification_config'
   AND NOT jsonb_exists(value, 'AGENT_CONC_LIMIT_MAP');
