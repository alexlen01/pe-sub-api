-- Canonicalize the fifth Agent LP Category while preserving support for legacy LP records.
-- V1_5 may already be applied, so this migration updates existing JSON configuration in place.

UPDATE config
   SET value = (
       SELECT jsonb_agg(
           CASE WHEN row->>'cls' IN ('Ineligible Investor', 'Ineligible Investors')
                THEN jsonb_set(row, '{cls}', '"Excluded Investor"'::jsonb)
                ELSE row
           END
       )
       FROM jsonb_array_elements(value) AS row
   )
 WHERE key = 'agent_tiers'
   AND jsonb_typeof(value) = 'array';

UPDATE config
   SET value = jsonb_set(
     value,
     '{AGENT_RATE_MAP}',
     (COALESCE(value->'AGENT_RATE_MAP', '{}'::jsonb) - 'Ineligible Investor' - 'Ineligible Investors') ||
     '{"Excluded Investor":"0%"}'::jsonb
   )
 WHERE key = 'classification_config'
   AND jsonb_typeof(value) = 'object';

UPDATE config
   SET value = jsonb_set(
       value,
       '{AGENT_CLS_UBS_MAP}',
       (COALESCE(value->'AGENT_CLS_UBS_MAP', '{}'::jsonb) - 'Ineligible Investor' - 'Ineligible Investors') ||
       '{"Excluded Investor":"Excluded"}'::jsonb
   )
 WHERE key = 'classification_config'
   AND jsonb_typeof(value) = 'object';

UPDATE config
  SET value = jsonb_set(
     value,
     '{AGENT_CONC_LIMIT_MAP}',
     (COALESCE(value->'AGENT_CONC_LIMIT_MAP', '{}'::jsonb) - 'Ineligible Investor' - 'Ineligible Investors') ||
     '{"Excluded Investor":0}'::jsonb
  )
 WHERE key = 'classification_config'
   AND jsonb_typeof(value) = 'object'
   AND jsonb_exists(value, 'AGENT_CONC_LIMIT_MAP');