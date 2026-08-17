-- Parent/child LP Master resolution and the alias feedback loop.
-- Source: pe-sub-docs/LP_Mapping_and_Database_Architecture.md (Phase 3, Phase 5, Part 2).
--
-- lp_master is self-referencing rather than split into a separate parent table: a parent/sponsor
-- and a child/feeder carry identical attributes, so one table avoids duplicate schemas and UNION
-- reads. `parent` (VARCHAR) is retained as the display and ingest field — pe-sub-jobs, the Agent BB
-- extraction rows and lp_records all speak the name — while `parent_id` is the resolved link the
-- matching engine traverses. The two are kept consistent by LpMasterService on every write.

ALTER TABLE lp_master
    ADD COLUMN parent_id          INTEGER REFERENCES lp_master(id) ON DELETE SET NULL,
    ADD COLUMN is_ultimate_parent BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill the link from the existing display string. A `parent` naming a row that is not in
-- LP Master stays unresolved (parent_id NULL) and the row continues to read as its own ultimate
-- entity, which is what the resolution logic falls back to anyway.
UPDATE lp_master c
   SET parent_id = p.id
  FROM lp_master p
 WHERE p.investor_name = c.parent
   AND p.id <> c.id;

UPDATE lp_master SET is_ultimate_parent = (parent_id IS NULL);

CREATE INDEX idx_lp_master_parent_id ON lp_master(parent_id);

-- Feedback loop: an uploaded Agent BB string an analyst accepted against an LP Master record.
-- The next upload of that exact string resolves in O(1) at score 100 and skips fuzzy scoring,
-- while still running the same parent routing.
CREATE TABLE lp_aliases (
    id            SERIAL       PRIMARY KEY,
    lp_master_id  INTEGER      NOT NULL REFERENCES lp_master(id) ON DELETE CASCADE,
    uploaded_name VARCHAR(255) NOT NULL UNIQUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_lp_aliases_uploaded_name ON lp_aliases(uploaded_name);
CREATE INDEX idx_lp_aliases_lp_master_id  ON lp_aliases(lp_master_id);

-- The match queue proposes an LP *Master* record, which is a different thing from the facility
-- LP record match_queue_entries.matched_lp_id already points at (that one references lp_records
-- and is cleared whenever a facility's records are replaced). Routing needs the master id to
-- survive that, so it gets its own column. ON DELETE SET NULL: removing a curated LP Master row
-- must not delete queue history.
ALTER TABLE match_queue_entries
    ADD COLUMN matched_lp_master_id INTEGER REFERENCES lp_master(id) ON DELETE SET NULL;

CREATE INDEX idx_match_queue_lp_master ON match_queue_entries(matched_lp_master_id);
