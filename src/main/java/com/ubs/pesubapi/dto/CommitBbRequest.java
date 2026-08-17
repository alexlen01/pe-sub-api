package com.ubs.pesubapi.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /api/bb/run/{facilityId}.
 * Each row represents one LpRecord as classified and valued in the Run Shadow BB wizard step.
 * The API upserts these rows into the LP Master table and then computes the snapshot.
 *
 * <p>Keys mirror the LpRecord field names. The {@code @JsonAlias} entries accept the abbreviated
 * keys older clients send, so a stale pe-sub-ui build keeps working through a deploy.
 */
public record CommitBbRequest(List<CommitLpRow> lps) {

    public record CommitLpRow(
        // Identity & LP Category (BB_PROCESS_FLOW Step 4)
        @JsonAlias("name")
        String  investorName,
        String  parent,
        boolean spv,
        @JsonAlias("hq")
        boolean highQuality,
        @JsonAlias({"investor_type"})
        String  investorType,
        @JsonAlias({"inst_vs_hnw", "instVsHnw", "type"})
        String  institutionalOrHnw,
        @JsonAlias({"region", "region_location"})
        String  regionLocation,
        @JsonAlias("ig")
        boolean investmentGrade,
        @JsonAlias("agentCls")
        String  agentLpCategory,       // Agent LP Category (verbatim from Agent BB)
        @JsonAlias("agentClsSource")
        String  agentLpCategorySource, // EXTRACTED, DERIVED, or USER_EDITED
        @JsonAlias("cls")
        String  ubsLpCategory,         // UBS LP Category (follows Agent LP Category)
        // Ratings
        @JsonAlias("sp")
        String  spRating,
        @JsonAlias("mdy")
        String  moodysRating,
        @JsonAlias("fitch")
        String  fitchRating,
        // Financial Scale
        String  aum,
        String  nav,
        String  pensionAssets,
        // Percents and rates are fractions (0.91 = 91%). Percent-scaled and "91%"-style values are
        // still normalised on the way in (MoneyValues.fraction) so older clients keep working.
        BigDecimal fundingRatio,
        // Commitment Data
        @JsonAlias("capCommit")
        String  capitalCommitment,
        @JsonAlias("pctCapCommit")
        BigDecimal pctOfFundCommitments,
        @JsonAlias("calledCap")
        String  calledCapital,
        // Uncalled / Eligible Capital
        @JsonAlias("uc")
        String  uncalledCapital,
        @JsonAlias("pctUncalled")
        BigDecimal pctOfFundUncalled,
        @JsonAlias("pctCalled")
        BigDecimal pctLpCalled,
        // Concentration & BB inputs. Engine outputs (borrowing bases, excess concentrations, rank)
        // are deliberately NOT part of this payload — the server computes and persists them at run
        // time, so a client can never submit BB figures the engine did not produce.
        @JsonAlias({"agent_conc_limit", "agentConcLimit", "agentConc"})
        String  agentConcentrationLimit,
        @JsonAlias({"ubs_conc_limit", "ubsConcLimit", "ubsConc"})
        String  ubsConcentrationLimit,  // per-LP UBS concentration limit percent, e.g. "7.5%"
        @JsonAlias("ubsRate")
        BigDecimal ubsAdvanceRate,      // per-LP UBS advance rate fraction, e.g. 0.90
        @JsonAlias("agentRate")
        BigDecimal agentAdvanceRate,
        // Status
        @JsonAlias("inc")
        boolean included,
        @JsonAlias("rcl")
        boolean reclassified,
        @JsonAlias("tf")
        boolean transferee,
        String  notes
    ) {}
}
