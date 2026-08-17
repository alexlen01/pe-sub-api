package com.ubs.pesubapi.dto;

/**
 * One LP Master row from the pe-sub-jobs LP Master feed (analyst-compiled CSV).
 * Upserted by {@code investorName}: the whole profile — identity, LP category, ratings,
 * financial scale, and the UBS credit-profile defaults — is replaced with the feed values,
 * matching the feed's authoritative-source semantics.
 *
 * <p>Values stay Strings on this CSV-derived boundary; {@code fundingRatio} and
 * {@code ubsDefaultAdvanceRate} are normalised to stored fractions by
 * {@code MoneyValues.fraction}, and {@code ubsDefaultConcentrationLimit} through
 * {@code MoneyValues.concLimit} because it may be a percent or a dollar cap.
 */
public record LpMasterIngestRow(
        String investorName,
        String parent,
        boolean spv,
        boolean highQuality,
        String investorType,
        String institutionalOrHnw,
        String regionLocation,
        boolean investmentGrade,
        String spRating,
        String moodysRating,
        String fitchRating,
        String aum,
        String nav,
        String pensionAssets,
        String fundingRatio,
        String ubsLpCategory,
        String ubsDefaultAdvanceRate,
        String ubsDefaultConcentrationLimit,
        String notes) {}
