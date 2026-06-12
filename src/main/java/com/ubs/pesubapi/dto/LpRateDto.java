package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.LpRate;

public record LpRateDto(
    Integer lpId,
    String  lpName,
    String  classification,
    double  ubsAdvRatePct,    // decimal e.g. 0.9 = 90%
    double  ubsConcLimitPct,  // decimal e.g. 0.075 = 7.5%
    String  effectiveDate     // ISO date YYYY-MM-DD
) {
    public static LpRateDto from(LpRate r, String lpName) {
        return new LpRateDto(
            r.getLpId(),
            lpName,
            r.getClassification(),
            r.getUbsAdvRatePct().doubleValue(),
            r.getUbsConcLimitPct().doubleValue(),
            r.getEffectiveDate().toString()
        );
    }
}
