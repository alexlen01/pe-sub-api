package com.ubs.pesubapi.dto;

import java.util.List;

/**
 * Request body for POST /api/bb/run/{facilityId}.
 * Each row represents one LP as classified and valued in the Run Shadow BB wizard step.
 * The API upserts these rows into the LP Master table and then computes the snapshot.
 */
public record CommitBbRequest(List<CommitLpRow> lps) {

    public record CommitLpRow(
        // Identity & Classification (BB_PROCESS_FLOW Step 4)
        String  name,
        String  parent,
        boolean spv,
        boolean hq,
        String  type,
        String  region,
        boolean ig,
        String  agentCls,       // Agent LP Classification (verbatim from Agent BB)
        String  cls,            // UBS LP Classification (follows Agent LP Classification)
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
        String  ubsConc,           // per-LP UBS dollar limit, e.g. "$25.0M"
        String  agentRate,
        String  abb,               // Agent Borrowing Base
        String  ubb,               // UBS Borrowing Base
        String  agentExcessConc,   // Agent Excess Concentration Base
        String  ubsExcessConc,     // UBS Excess Concentration Base
        // Status
        boolean inc,
        boolean rcl,
        String  notes
    ) {}
}
