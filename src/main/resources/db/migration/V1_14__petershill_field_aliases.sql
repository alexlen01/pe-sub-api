-- Petershill IV current workbook headers. These aliases let the generic extraction
-- pipeline map LP rows after structural recognition has selected the Petershill profile.

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank)
SELECT cf.id, v.alias_sort, v.alias_text, 'Bank', 'WF'
FROM (VALUES
  ('Investor Name',              20, 'Deal Investor Name'),
  ('S&P Rating',                 20, 'Investor S&P'),
  ('Moody''s Rating',            20, 'Investor Moody'),
  ('NAV',                        20, 'NAV Range (USD)'),
  ('Capital Commitments',        20, 'Original Commitment'),
  ('Uncalled Capital',           20, 'Unfunded Capital Commitment'),
  ('% of Uncalled Capital',      20, '% of Unfunded Commitment'),
  ('% of Eligible Uncalled',     20, '% of Eligible Unfunded Commitment'),
  ('Eligible Commitment',        20, 'Eligible Commitment'),
  ('Excess Concentration',       20, 'Excess Concentration'),
  ('Borrowing Base',             20, 'Borrowing Base Contribution')
) AS v(canonical, alias_sort, alias_text)
JOIN fm_canonical_fields cf ON cf.canonical = v.canonical
WHERE NOT EXISTS (
  SELECT 1
  FROM fm_aliases a
  WHERE a.canonical_field_id = cf.id
    AND LOWER(a.alias_text) = LOWER(v.alias_text)
);
