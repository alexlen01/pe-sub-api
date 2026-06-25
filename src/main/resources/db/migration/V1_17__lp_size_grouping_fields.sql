-- Add LP Size Grouping fields before AUM in Financial Scale.

UPDATE fm_canonical_fields
SET field_sort = field_sort + 2
WHERE group_name = 'Financial Scale'
  AND canonical NOT IN ('Size Metric Type', 'Size Value / Tier')
  AND NOT EXISTS (
      SELECT 1 FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'
  )
  AND NOT EXISTS (
      SELECT 1 FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'
  );

INSERT INTO fm_canonical_fields
    (group_name, group_sort, field_sort, canonical, lp_master_field, disambiguation, extraction_key, is_derived)
SELECT
    'Financial Scale', 4, 1,
    'Size Metric Type',
    'Financial Scale - Size Metric Type',
    'The metric used by the agent to classify LP size, such as AUM, NAV, net worth, commitments, or a named size category',
    NULL, FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM fm_canonical_fields WHERE canonical = 'Size Metric Type'
);

INSERT INTO fm_canonical_fields
    (group_name, group_sort, field_sort, canonical, lp_master_field, disambiguation, extraction_key, is_derived)
SELECT
    'Financial Scale', 4, 2,
    'Size Value / Tier',
    'Financial Scale - Size Value / Tier',
    'The reported LP size value, bucket, or tier corresponding to the selected size metric type',
    NULL, FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM fm_canonical_fields WHERE canonical = 'Size Value / Tier'
);

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank)
SELECT cf.id, v.alias_sort, v.alias_text, 'Core', NULL
FROM fm_canonical_fields cf
JOIN (VALUES
    ('Size Metric Type', 1, 'Size Metric Type'),
    ('Size Metric Type', 2, 'LP Size Metric'),
    ('Size Metric Type', 3, 'Size Metric'),
    ('Size Metric Type', 4, 'Scale Metric'),
    ('Size Metric Type', 5, 'Financial Size Metric'),
    ('Size Metric Type', 6, 'Metric Type'),
    ('Size Value / Tier', 1, 'Size Value / Tier'),
    ('Size Value / Tier', 2, 'Size Tier'),
    ('Size Value / Tier', 3, 'LP Size Tier'),
    ('Size Value / Tier', 4, 'Size Value'),
    ('Size Value / Tier', 5, 'Financial Size Tier'),
    ('Size Value / Tier', 6, 'Investor Size Tier'),
    ('Size Value / Tier', 7, 'Size Bucket')
) AS v(canonical, alias_sort, alias_text) ON cf.canonical = v.canonical
WHERE NOT EXISTS (
    SELECT 1
    FROM fm_aliases a
    WHERE LOWER(a.alias_text) = LOWER(v.alias_text)
);
