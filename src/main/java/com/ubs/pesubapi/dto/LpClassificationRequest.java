package com.ubs.pesubapi.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;
import java.util.List;

/**
 * Batch save of the credit officer's LP Category &amp; rate decisions from the
 * "LP Category &amp; Rate Assignment" screen onto persisted LP records.
 * Rows are matched to existing records by (facilityId, investorName); unmatched rows are ignored.
 *
 * <p>The four {@code *Pct} rate inputs are percent-scaled (90.0, 7.5) because that is what the
 * screen's number fields hold; they are stored as fractions. Every other percent on this payload is
 * already a fraction. The names keep the {@code Pct} suffix precisely to mark that difference.
 *
 * <p>The screen auto-saves each edited row individually as the user types, so those calls leave
 * {@code audit} null/false to avoid one audit entry per keystroke. A single aggregated entry is
 * written only on the final flush sent when the user leaves the screen, which sets {@code audit}
 * to {@code true}.
 */
public record LpClassificationRequest(
    Integer facilityId,
    String  effectiveDate,        // YYYY-MM; null → current month
    Boolean audit,                // true → write one aggregated audit entry; null/false → silent
    List<Row> rows
) {
    public record Row(
        Integer id,
        @JsonAlias("name")
        String  investorName,
        String  originalName,
        // Identity & LP Category (manual)
        String  parent,
        Boolean spv,
        @JsonAlias({"investor_type"})
        String  investorType,
        @JsonAlias({"inst_vs_hnw", "instVsHnw", "type"})
        String  institutionalOrHnw,
        @JsonAlias({"region", "region_location"})
        String  regionLocation,
        @JsonAlias("ig")
        Boolean investmentGrade,
        @JsonAlias("agentCls")
        String  agentLpCategory,        // Agent LP Category (verbatim from Agent BB)
        @JsonAlias("agentClsSource")
        String  agentLpCategorySource,  // EXTRACTED, DERIVED, or USER_EDITED
        @JsonAlias("cls")
        String  ubsLpCategory,          // UBS LP Category (follows Agent LP Category)
        @JsonAlias("sp")
        String  spRating,
        @JsonAlias("mdy")
        String  moodysRating,
        @JsonAlias("fitch")
        String  fitchRating,
        // Scale (manual)
        String  aum,                    // Assets Under Management
        String  nav,                    // Net Asset Value
        String  pensionAssets,
        BigDecimal fundingRatio,        // Pension funded status as a fraction (0.91 = 91%)
        // Commitment / capital (manual)
        @JsonAlias("capCommit")
        String  capitalCommitment,
        @JsonAlias("uc")
        String  uncalledCapital,
        // Rates & limits (manual; percent-scaled, e.g. 90.0 / 7.5)
        @JsonAlias("ubsAdvRatePct")
        Double  ubsAdvanceRatePct,      // null → rate left unchanged
        @JsonAlias("agentRatePct")
        Double  agentAdvanceRatePct,    // null → unchanged
        @JsonAlias("ubsConcLimitPct")
        Double  ubsConcentrationLimitPct,   // null → limit left unchanged
        @JsonAlias("agentConcLimitPct")
        Double  agentConcentrationLimitPct, // null → unchanged
        // Status (manual)
        @JsonAlias("inc")
        Boolean included,
        @JsonAlias("tf")
        Boolean transferee,
        String  notes
    ) {}
}
