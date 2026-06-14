-- Enforce one LP record per (facility_id, investor_name).
--
-- LP Master is a bank-wide store, but an LP's participation is tracked per facility,
-- so the same investor name may appear once in each facility — never twice in one.
-- Re-submitting the same Agent BB must update the existing records in place rather than
-- create a second, duplicated set. This constraint is the structural guarantee behind the
-- idempotent (facility_id, investor_name) upsert performed by the ingest/commit path.

-- Dedup any pre-existing rows before adding the constraint, keeping the lowest id per
-- (facility, name) and repointing child references at the surviving row.
CREATE TEMP TABLE lp_dupe_map AS
SELECT id,
       MIN(id) OVER (PARTITION BY facility_id, investor_name) AS keep_id
FROM lp_records;

-- Redundant rate rows on duplicates are dropped; the surviving row carries its own.
DELETE FROM lp_rates
WHERE lp_id IN (SELECT id FROM lp_dupe_map WHERE id <> keep_id);

-- Repoint match-queue references to the surviving LP record.
UPDATE match_queue_entries m
SET matched_lp_id = d.keep_id
FROM lp_dupe_map d
WHERE m.matched_lp_id = d.id AND d.id <> d.keep_id;

DELETE FROM lp_records
WHERE id IN (SELECT id FROM lp_dupe_map WHERE id <> keep_id);

DROP TABLE lp_dupe_map;

ALTER TABLE lp_records
    ADD CONSTRAINT uq_lp_records_facility_investor UNIQUE (facility_id, investor_name);
