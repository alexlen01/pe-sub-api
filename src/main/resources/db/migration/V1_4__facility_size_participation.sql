-- Facility-level borrowing-base inputs for the Shadow BB "Borrowing Base" summary
-- (SHADOW_BB_ANALYSIS Table 2). Both are stated dollar amounts from the credit agreement /
-- participation schedule; the derived metrics (UBS Participation Rate, Facility LTV, Available
-- Commitment, Facility Advance Rate) are computed from these plus the LP-level BB totals.
-- Nullable: facilities created before this migration (or before the inputs are known) have no
-- value and surface as blank/"—" in the UI rather than a misleading $0.
ALTER TABLE facilities ADD COLUMN facility_size    NUMERIC(15, 2);
ALTER TABLE facilities ADD COLUMN ubs_participation NUMERIC(15, 2);
