package com.ubs.pesubapi.dto;

/**
 * One row from the pe-sub-jobs facility-LP seed feed. Carries the full per-LP column set of
 * the LP DB Export (facility-level columns excluded) so every column lands on the lp_records
 * insert. ubsCls arrives pre-derived by the extract (row UBSAR via the Run-Shadow-BB rate
 * tiers). Booleans travel as "TRUE"/"FALSE" strings; blank row values fall back to the LP
 * Master golden profile server-side. The API resolves the facility and LP Master references
 * by name; rows whose facility or LP Master cannot be resolved, or whose (facility, investor)
 * pair already has an LP record, are skipped — never overwritten.
 */
public record LpRecordSeedRow(
        String facilityName,
        String investorName,
        String capCommit,
        String uncalled,
        String agentCls,
        String agentRate,
        String agentConc,
        String parent,
        String spv,
        String highQty,
        String investorType,
        String instVsHnw,
        String regionLocation,
        String investmentGrade,
        String ubsCls,
        String sp,
        String mdy,
        String fitch,
        String aum,
        String nav,
        String pension,
        String pensionFunded,
        String pctCapCommit,
        String calledCap,
        String pctUncalled,
        String pctCalled,
        String ubsConc,
        String ubsRate,
        String agentBb,
        String ubsBb,
        String notes) {}
