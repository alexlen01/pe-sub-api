package com.ubs.pesubapi.dto;

/** UBS exposure aggregated per agent bank from each facility's latest BB snapshot.
 *  Money fields in $millions. */
public record AgentBankExposureDto(
    String agentBank,
    int    facilityCount,
    int    lpCount,
    double ubsBBM,
    double agentBBM,
    double deltaM
) {}
