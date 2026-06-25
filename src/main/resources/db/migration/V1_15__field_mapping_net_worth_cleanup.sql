-- Add Net Worth to Financial Scale and remove the retired Goldman numeric rating helpers.

UPDATE fm_canonical_fields
SET field_sort = field_sort + 1
WHERE group_name = 'Financial Scale'
  AND field_sort >= 3
  AND NOT EXISTS (
      SELECT 1 FROM fm_canonical_fields WHERE canonical = 'Net Worth'
  );

INSERT INTO fm_canonical_fields
    (group_name, group_sort, field_sort, canonical, lp_master_field, disambiguation, extraction_key, is_derived)
SELECT
    'Financial Scale', 4, 3,
    'Net Worth',
    'Financial Scale - Net Worth',
    'Total net worth of the LP or investor entity, as reported by the agent',
    NULL, FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM fm_canonical_fields WHERE canonical = 'Net Worth'
);

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank)
SELECT cf.id, v.alias_sort, v.alias_text, 'Core', NULL
FROM fm_canonical_fields cf
JOIN (VALUES
    (1, 'Net Worth'),
    (2, 'Total Net Worth'),
    (3, 'Investor Net Worth'),
    (4, 'Networth'),
    (5, 'Net Worth (USD)'),
    (6, 'Net Worth Range'),
    (7, 'NW')
) AS v(alias_sort, alias_text) ON TRUE
WHERE cf.canonical = 'Net Worth'
  AND NOT EXISTS (
      SELECT 1 FROM fm_aliases a
      WHERE LOWER(a.alias_text) = LOWER(v.alias_text)
  );

DELETE FROM fm_aliases
WHERE canonical_field_id IN (
    SELECT id
    FROM fm_canonical_fields
    WHERE canonical IN ('S&P Numeric Score', 'Moody''s Numeric Score', 'Numeric Rating')
);

DELETE FROM fm_canonical_fields
WHERE canonical IN ('S&P Numeric Score', 'Moody''s Numeric Score', 'Numeric Rating');
