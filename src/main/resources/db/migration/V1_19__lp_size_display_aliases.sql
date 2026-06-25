-- Map Shadow BB LP Size display inputs to the canonical size value/type fields.

INSERT INTO fm_aliases (canonical_field_id, alias_sort, alias_text, tier, bank)
SELECT cf.id, v.alias_sort, v.alias_text, 'Core', NULL
FROM fm_canonical_fields cf
JOIN (VALUES
    ('Size Metric Type', 20, 'LP Size Criteria'),
    ('Size Value / Tier', 20, 'LP Size ($ Bil)')
) AS v(canonical, alias_sort, alias_text) ON cf.canonical = v.canonical
WHERE NOT EXISTS (
    SELECT 1
    FROM fm_aliases a
    WHERE LOWER(a.alias_text) = LOWER(v.alias_text)
);
