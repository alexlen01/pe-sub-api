package com.ubs.pesubapi.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request body for POST /api/bb/run/{facilityId}.
 * Each row represents one LpRecord as classified and valued in the Run Shadow BB wizard step.
 * The API upserts these rows into the LP Master table and then computes the snapshot.
 */
public record CommitBbRequest(List<CommitLpRow> lps) {

    public record CommitLpRow(
        // Identity & Classification (BB_PROCESS_FLOW Step 4)
        String  name,
        String  parent,
        boolean spv,
        boolean hq,
        @JsonProperty("fund_sleeve")
        @JsonAlias({"fundSleeve"})
        String  fundSleeve,
        @JsonProperty("investor_type")
        @JsonAlias({"investorType"})
        String  investorType,
        @JsonProperty("inst_vs_hnw")
        @JsonAlias({"instVsHnw", "type"})
        String  instVsHnw,
        @JsonProperty("region_location")
        @JsonAlias({"region", "regionLocation"})
        String  regionLocation,
        boolean ig,
        String  agentCls,       // Agent LP Category (verbatim from Agent BB)
        String  agentClsSource, // EXTRACTED, DERIVED, or USER_EDITED
        String  cls,            // UBS LP Category (follows Agent LP Category)
        // Ratings
        String  sp,
        String  mdy,
        String  fitch,
        // Financial Scale
        String  aum,
        String  nav,
        String  pension,
        String  pensionFunded,
        // Commitment Data
        String  capCommit,
        String  pctCapCommit,
        String  calledCap,
        // Uncalled / Eligible Capital
        String  uc,
        String  pctUncalled,
        String  pctCalled,
        // Concentration & BB
        String  agentConc,
        String  ubsConc,           // per-LP UBS concentration limit percent, e.g. "7.5%"
        String  agentRate,
        String  abb,               // Agent Borrowing Base
        String  ubb,               // UBS Borrowing Base
        String  agentExcessConc,   // Agent Excess Concentration Base
        String  ubsExcessConc,     // UBS Excess Concentration Base
        // Status
        boolean inc,
        boolean rcl,
        boolean tf,
        Integer rank,              // calculated by Shadow BB run; persisted to LP records
        String  notes
    ) {}
}
