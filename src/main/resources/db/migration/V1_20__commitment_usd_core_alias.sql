-- Commitment (USD) appears across multiple agent templates, not only BNY.
-- Treat it as a global amount alias so it cannot fuzzy-match percentage fields.
UPDATE fm_aliases
SET tier = 'Core',
    bank = NULL
WHERE alias_text = 'Commitment (USD)'
  AND canonical_field_id = (
    SELECT id FROM fm_canonical_fields WHERE canonical = 'Capital Commitments'
  );
