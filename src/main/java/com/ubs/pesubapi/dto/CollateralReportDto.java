package com.ubs.pesubapi.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Collateral Market Value & Coverage report — one BB snapshot summarised for the certificate. */
public record CollateralReportDto(
    Integer       facilityId,
    String        facilityName,
    String        agentBank,
    Integer       snapshotId,
    LocalDateTime calculatedAt,
    BbSummary     summary,
    double        totalEligibleUncalledM,
    List<ClassBreakdownRow> classBreakdown
) {
    /** One LP-classification tier of the certificate breakdown table. All money in $millions;
     *  the advance rate is a fraction (0.91 = 91%), formatted by the consumer. */
    public record ClassBreakdownRow(
        String ubsLpCategory,
        int    count,
        double uncalledM,
        double ubbM,
        BigDecimal ubsAdvanceRate
    ) {}
}
