package com.ubs.pesubapi.dto;

/**
 * One row from the pe-sub-jobs facility-LP seed feed. Carries the full per-LP column set of
 * the LP DB Export (facility-level columns excluded) so every column lands on the lp_records
 * insert. {@code ubsLpCategory} arrives pre-derived by the extract (row UBSAR via the
 * Run-Shadow-BB rate tiers). Booleans travel as "TRUE"/"FALSE" strings; blank row values fall
 * back to the LP Master golden profile server-side. The API resolves the facility and LP Master
 * references by name; rows whose facility or LP Master cannot be resolved, or whose
 * (facility, investor) pair already has an LP record, are skipped — never overwritten.
 *
 * <p>Every value stays a String on this boundary because the feed is CSV-derived: rates and
 * percents may arrive as "90%", "90" or "0.9" and are normalised to the stored fraction by
 * {@code MoneyValues.fraction}, exactly as money goes through {@code MoneyValues.dollars}.
 */
public record LpRecordSeedRow(
        String facilityName,
        String investorName,
        String capitalCommitment,
        String uncalledCapital,
        String agentLpCategory,
        String agentAdvanceRate,
        String agentConcentrationLimit,
        String parent,
        String spv,
        String highQuality,
        String investorType,
        String institutionalOrHnw,
        String regionLocation,
        String investmentGrade,
        String ubsLpCategory,
        String spRating,
        String moodysRating,
        String fitchRating,
        String aum,
        String nav,
        String pensionAssets,
        String fundingRatio,
        String pctOfFundCommitments,
        String calledCapital,
        String pctOfFundUncalled,
        String pctLpCalled,
        String ubsConcentrationLimit,
        String ubsAdvanceRate,
        String agentBorrowingBase,
        String ubsBorrowingBase,
        String notes) {}
