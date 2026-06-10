-- Track which wizard step each submission has reached and persist Run Shadow BB overrides.
-- wizard_step: 1=upload, 3=extraction review, 4=match queue, 5=run shadow BB
-- shadow_bb_overrides: LP classification/rate overrides set by the credit officer on Step 5

ALTER TABLE submissions
    ADD COLUMN wizard_step          INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN shadow_bb_overrides  JSONB;
