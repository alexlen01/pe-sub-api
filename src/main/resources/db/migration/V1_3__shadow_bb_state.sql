-- Track which wizard step each submission has reached and persist Run Shadow BB overrides.
--
-- wizard_step mirrors the 1-indexed WIZARD_STEPS array in the UI:
--   1 = Select Facility / Upload (default; also set when extraction fails with status='Error')
--   2 = Upload Document — transient; extraction runs inline so submissions jump directly to 3
--   3 = Review Extraction (status='Review'; awaiting credit officer action)
--   4 = Review Matches   (after POST /{id}/confirm)
--   5 = LP Classification & Rate Assignment (after PATCH /{id}/shadow-bb-state)
--
-- shadow_bb_overrides: JSONB map of LP key → {cls, rate} overrides committed on Step 5

ALTER TABLE submissions
    ADD COLUMN wizard_step          INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN shadow_bb_overrides  JSONB;
