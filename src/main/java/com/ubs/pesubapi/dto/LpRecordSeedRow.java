package com.ubs.pesubapi.dto;

/**
 * One row from the pe-sub-jobs facility-LP seed feed: an LP's commitment terms within a named
 * facility. The API resolves the facility and LP Master references by name and merges the LP
 * Master profile server-side; rows whose facility or LP Master cannot be resolved, or whose
 * (facility, investor) pair already has an LP record, are skipped — never overwritten.
 */
public record LpRecordSeedRow(
        String facilityName,
        String investorName,
        String capCommit,
        String uncalled,
        String agentCls,
        String agentRate,
        String agentConc) {}
