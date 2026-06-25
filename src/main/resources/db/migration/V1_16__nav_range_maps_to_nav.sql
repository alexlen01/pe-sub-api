-- NAV Range (USD) is a true NAV field, not AUM.

DELETE FROM fm_aliases a
USING fm_canonical_fields cf
WHERE a.canonical_field_id = cf.id
  AND LOWER(a.alias_text) = LOWER('NAV Range (USD)')
  AND cf.canonical <> 'NAV';

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank)
SELECT cf.id, 20, 'NAV Range (USD)', 'Bank', 'WF'
FROM fm_canonical_fields cf
WHERE cf.canonical = 'NAV'
  AND NOT EXISTS (
      SELECT 1
      FROM fm_aliases a
      WHERE a.canonical_field_id = cf.id
        AND LOWER(a.alias_text) = LOWER('NAV Range (USD)')
  );
