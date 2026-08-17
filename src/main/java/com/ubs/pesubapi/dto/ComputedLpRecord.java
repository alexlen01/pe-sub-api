package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.LpRecord;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.ubs.pesubapi.json.PercentFractionDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

/**
 * One LP as computed by the BB engine, and the row shape persisted inside a {@code bb_snapshots}
 * result. Keys mirror {@link LpRecord} and are camelCase throughout.
 *
 * <p>Every {@code @JsonAlias} here names a key used by snapshots written before the columns were
 * spelled out. Snapshots are historical records that must keep loading, so the aliases are load
 * bearing — see {@link com.ubs.pesubapi.entity.converter.BbResultConverter}.
 *
 * <p>{@code highQuality} and {@code highQualityCategory} are different facts and were previously
 * both called some form of "high quality": the first is the LP's stored quality flag, the second is
 * derived from the UBS LP Category being one of the top BB tiers.
 */
public record ComputedLpRecord(
    Integer id,
    Integer facilityId,
    @JsonAlias("name")
    String  investorName,
    String  parent,
    boolean spv,
    @JsonAlias("hq")
    boolean highQuality,
    @JsonAlias("investor_type")
    String  investorType,
    @JsonAlias({"inst_vs_hnw", "instVsHnw"})
    String  institutionalOrHnw,
    @JsonAlias("region_location")
    String  regionLocation,
    @JsonAlias("ig")
    boolean investmentGrade,
    @JsonAlias("cls")
    String  ubsLpCategory,
    @JsonAlias("sp")
    String  spRating,
    @JsonAlias("mdy")
    String  moodysRating,
    @JsonAlias("fitch")
    String  fitchRating,
    String  aum,
    @JsonAlias("uc")
    String  uncalledCapital,
    @JsonAlias("abb")
    String  agentBorrowingBase,
    @JsonAlias("inc")
    boolean included,
    @JsonAlias("rcl")
    boolean reclassified,
    @JsonAlias("tf")
    boolean transferee,
    // Advance rates travel as fractions (0.91 = 91%), like their lp_records columns; pe-sub-ui
    // formats them. The money fields around them stay pre-formatted display strings.
    // Snapshots written before rates went NUMERIC hold "90%" here, hence the tolerant reader.
    @JsonAlias("rate")
    @JsonDeserialize(using = PercentFractionDeserializer.class)
    BigDecimal ubsAdvanceRate,
    @JsonAlias("agentRate")
    @JsonDeserialize(using = PercentFractionDeserializer.class)
    BigDecimal agentAdvanceRate,
    @JsonAlias("uec")
    String  uncalledEligibleCapital,
    @JsonAlias("ubb")
    String  ubsBorrowingBase,
    String  delta,
    double  uecM,
    double  ubbM,
    double  abbM,
    double  deltaM,
    double  concExcessM,
    double  ucM,
    double  agentExcessM,
    double  pctAgentBB,
    double  pctUbsBB,
    @JsonAlias("highQuality")
    boolean highQualityCategory
) {
    /** UBS LP Categories that count as top-tier for BB quality reporting. */
    private static final java.util.Set<String> HIGH_QUALITY_CATEGORIES = java.util.Set.of(
        "Rated Investor",
        "Unrated NAV > $1Bn",
        "FoF & Other > $10Bn AUM",
        "Corp Pension > $5Bn Assets");

    public static ComputedLpRecord from(LpRecord lpRecord, double busaRate, double ucM, double uecM,
                                   double ubbM, double abbM, double deltaM, double concExcessM,
                                   double agentExcessM) {
        return new ComputedLpRecord(
            lpRecord.getId(), lpRecord.getFacilityId(), lpRecord.getInvestorName(), lpRecord.getParent(),
            lpRecord.isSpv(), lpRecord.isHighQuality(), lpRecord.getInvestorType(),
            lpRecord.getInstitutionalOrHnw(), lpRecord.getRegionLocation(), lpRecord.isInvestmentGrade(),
            lpRecord.getUbsLpCategory(), lpRecord.getSpRating(), lpRecord.getMoodysRating(),
            lpRecord.getFitchRating(),
            lpRecord.getAum() != null ? lpRecord.getAum() : "",
            lpRecord.getUncalledCapital() != null ? lpRecord.getUncalledCapital().toString() : "",
            lpRecord.getAgentBorrowingBase() != null ? lpRecord.getAgentBorrowingBase().toString() : "",
            lpRecord.isIncluded(), lpRecord.isReclassified(), lpRecord.isTransferee(),
            BigDecimal.valueOf(busaRate), lpRecord.getAgentAdvanceRate(),
            fmtM(uecM), fmtM(ubbM), fmtM(deltaM),
            uecM, ubbM, abbM, deltaM, concExcessM, ucM, agentExcessM,
            0, 0,   // %-of-BB shares need portfolio totals — set by withShares in the compute pass 2
            HIGH_QUALITY_CATEGORIES.contains(lpRecord.getUbsLpCategory())
        );
    }

    /** Copy with the %-of-portfolio shares filled in once the portfolio totals are known. */
    public ComputedLpRecord withShares(double pctAgentBB, double pctUbsBB) {
        return new ComputedLpRecord(
            id, facilityId, investorName, parent, spv, highQuality, investorType, institutionalOrHnw,
            regionLocation, investmentGrade, ubsLpCategory, spRating, moodysRating, fitchRating,
            aum, uncalledCapital, agentBorrowingBase, included, reclassified, transferee,
            ubsAdvanceRate, agentAdvanceRate, uncalledEligibleCapital, ubsBorrowingBase, delta,
            uecM, ubbM, abbM, deltaM, concExcessM, ucM, agentExcessM,
            pctAgentBB, pctUbsBB, highQualityCategory
        );
    }

    private static String fmtM(double m) {
        if (m == 0) return "$0";
        double abs = Math.abs(m);
        return (m < 0 ? "-" : "") + "$" + String.format("%.1f", abs) + "M";
    }
}
