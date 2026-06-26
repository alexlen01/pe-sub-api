-- Reorder the Field Mapping Dictionary and add Notes as a canonical field.

UPDATE fm_canonical_fields
SET group_sort = CASE group_name
    WHEN 'Identity & Classification' THEN 1
    WHEN 'Ratings' THEN 2
    WHEN 'Commitment Data' THEN 3
    WHEN 'Uncalled Data' THEN 4
    WHEN 'Financial Scale' THEN 5
    WHEN 'Borrowing Base' THEN 6
    WHEN 'Concentration' THEN 7
    WHEN 'Notes' THEN 8
    ELSE group_sort
END
WHERE group_name IN (
    'Identity & Classification',
    'Ratings',
    'Commitment Data',
    'Uncalled Data',
    'Financial Scale',
    'Borrowing Base',
    'Concentration',
    'Notes'
);

INSERT INTO fm_canonical_fields
    (group_name, group_sort, field_sort, canonical, lp_master_field, disambiguation, extraction_key, is_derived)
SELECT
    'Notes', 8, 1,
    'Notes',
    'Notes - Notes',
    'Free-text analyst notes captured from agent borrowing base submissions',
    'NOTES', FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM fm_canonical_fields WHERE canonical = 'Notes'
);

UPDATE fm_canonical_fields
SET group_name = 'Notes',
    group_sort = 8,
    field_sort = 1,
    lp_master_field = 'Notes - Notes',
    extraction_key = 'NOTES',
    is_derived = FALSE
WHERE canonical = 'Notes';

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank)
SELECT cf.id, v.alias_sort, v.alias_text, 'Core', NULL
FROM fm_canonical_fields cf
JOIN (VALUES
    (1, 'Notes'),
    (2, 'Analyst Notes'),
    (3, 'LP Notes'),
    (4, 'Comments'),
    (5, 'Remarks')
) AS v(alias_sort, alias_text) ON TRUE
WHERE cf.canonical = 'Notes'
  AND NOT EXISTS (
      SELECT 1
      FROM fm_aliases a
      WHERE LOWER(a.alias_text) = LOWER(v.alias_text)
  );
