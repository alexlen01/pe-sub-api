-- ── Sampled Agent BB template profiles (BB_Templates.xlsx) ─────────────────────
-- Five real-world Agent BB layouts captured in pe-sub-platform/public/BB_Templates.xlsx.
-- Unlike the auto-learned bank templates above, the sample identifies each layout by
-- FUND / DEAL (the agent bank was not recorded), so agent_bank holds the fund/deal label
-- as the template key until the owning facility is onboarded with its real agent bank.
--
-- header_row_index is 0-based (the extraction engine convention): Excel row N → N-1.
-- summary_rows_above_header / header_row_index therefore equal the count of rows above
-- the column header. classification is the agent's verbatim section label — these
-- templates use bespoke labels ("...Investors", feeder vehicles) that the six standard
-- Agent LP Classification values do not cover, and must not be normalised to a UBS tier.

-- Stacked column headers (e.g. Carlyle CP VII rows 84-85) occupy more than one physical
-- row; the engine joins `header_row_span` consecutive rows into one logical header.
ALTER TABLE bb_template_tabs
    ADD COLUMN header_row_span INTEGER NOT NULL DEFAULT 1;

INSERT INTO bb_templates
    (agent_bank, template_class, sheet_name, header_row_index, auto_learned,
     tranche_count, has_grouping_rows, has_color_flags, summary_rows_above_header)
VALUES
    -- KKR Ascendant — single "Borrowing Base" tab; 6 LP-category sections; summary rows 2-9.
    ('KKR Ascendant Fund', 'A', NULL, NULL, FALSE, 1, TRUE,  FALSE, 9),
    -- Audax VII — multiple "Investor List" tabs (one per borrower); flat list, no sections.
    ('Audax Fund VII',     'A', NULL, NULL, FALSE, 1, FALSE, FALSE, 12),
    -- Comvest CCP VII — multiple "Investor List" tabs; 5 feeder-vehicle sections.
    ('CCP VII Lev M & M',  'A', NULL, NULL, FALSE, 1, TRUE,  FALSE, 6),
    -- Aurora AEP VII — single "BB" tab; 4 LP-category sections; cell-format legend (colour flags).
    ('AEP VII',            'A', NULL, NULL, FALSE, 1, TRUE,  TRUE,  10),
    -- Carlyle CP VII — multiple "BB" tabs; flat list; deep title (row 83); stacked header (84-85).
    ('CP VII',             'A', NULL, NULL, FALSE, 1, FALSE, FALSE, 83);

-- LP_GRID tab per template: sheet name + 0-based header row (+ span for stacked headers).
INSERT INTO bb_template_tabs (template_id, tab_role, tab_sort, sheet_name, header_row_index, header_row_span)
SELECT t.id, 'LP_GRID', 1, v.sheet_name, v.header_row_index, v.header_row_span
FROM   bb_templates t
JOIN  (VALUES
    ('KKR Ascendant Fund', 'Borrowing Base',  9,  1),
    ('Audax Fund VII',     'Investor List',  12,  1),
    ('CCP VII Lev M & M',  'Investor List',   6,  1),
    ('AEP VII',            'BB',             10,  1),
    ('CP VII',             'BB',             83,  2)
) AS v(agent_bank, sheet_name, header_row_index, header_row_span)
  ON t.agent_bank = v.agent_bank;

-- KKR Ascendant — 6 LP-category sections (top → bottom).
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tb.id, g.group_sort, g.header_text, g.classification
FROM   bb_template_tabs tb
JOIN   bb_templates     tmpl ON tmpl.id = tb.template_id
CROSS JOIN (VALUES
    (1, 'Rated Included Investors',     'Rated Included Investors'),
    (2, 'Non-Rated Included Investors', 'Non-Rated Included Investors'),
    (3, 'Designated Investors',         'Designated Investors'),
    (4, 'Borrowing Base Investors',     'Borrowing Base Investors'),
    (5, 'Hurdle Investors',             'Hurdle Investors'),
    (6, 'Excluded Investors',           'Excluded Investors')
) AS g(group_sort, header_text, classification)
WHERE  tmpl.agent_bank = 'KKR Ascendant Fund' AND tb.tab_role = 'LP_GRID';

-- Comvest CCP VII — 5 feeder-vehicle sections.
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tb.id, g.group_sort, g.header_text, g.classification
FROM   bb_template_tabs tb
JOIN   bb_templates     tmpl ON tmpl.id = tb.template_id
CROSS JOIN (VALUES
    (1, 'Levered (Delaware) Feeder', 'Levered (Delaware) Feeder'),
    (2, '(Cayman) Feeder, L.P.',     '(Cayman) Feeder, L.P.'),
    (3, '(Delaware) Feeder, L.P.',   '(Delaware) Feeder, L.P.'),
    (4, 'Lux Intermediate',          'Lux Intermediate'),
    (5, 'Lux Non-Treaty Feeder',     'Lux Non-Treaty Feeder')
) AS g(group_sort, header_text, classification)
WHERE  tmpl.agent_bank = 'CCP VII Lev M & M' AND tb.tab_role = 'LP_GRID';

-- Aurora AEP VII — 4 LP-category sections.
INSERT INTO bb_template_groups (tab_id, group_sort, header_text, classification)
SELECT tb.id, g.group_sort, g.header_text, g.classification
FROM   bb_template_tabs tb
JOIN   bb_templates     tmpl ON tmpl.id = tb.template_id
CROSS JOIN (VALUES
    (1, 'Rated Included Investors',     'Rated Included Investors'),
    (2, 'Non-Rated Included Investors', 'Non-Rated Included Investors'),
    (3, 'Designated Investors',         'Designated Investors'),
    (4, 'Excluded Investors',           'Excluded Investors')
) AS g(group_sort, header_text, classification)
WHERE  tmpl.agent_bank = 'AEP VII' AND tb.tab_role = 'LP_GRID';
