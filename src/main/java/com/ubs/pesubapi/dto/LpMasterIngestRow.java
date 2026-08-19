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
 *
 * <p>{@code highQuality} is boxed on purpose. The LP DB Export stopped carrying the column, so
 * the field arrives absent — and as a primitive that would deserialise to {@code false},
 * silently flipping every LP out of the high-quality tier and firing the aggregate breach
 * checks that key off it. Null means "not supplied", which the service reads as the
 * schema default (TRUE).
 */
public record LpMasterIngestRow(
        String investorName,
        String parent,
        boolean spv,
        Boolean highQuality,
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
