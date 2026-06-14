-- Match Analysis output (Solution Design §6.5).
--
-- Each match-queue entry now persists a structured `match_details` JSONB payload alongside the
-- human-readable `reasons`: the normalised agent name, the winning confidence band, and the ranked
-- top-5 LP Master candidates with their Jaro-Winkler, Levenshtein and combined scores. This payload
-- drives the Match Analysis panel so the credit officer can see exactly why the algorithm ranked
-- candidates as it did. Nullable: pre-existing rows have no analysis until the queue is rebuilt.

ALTER TABLE match_queue_entries
    ADD COLUMN match_details JSONB;
