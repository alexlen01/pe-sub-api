package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.LpRecord;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ComputedLpRecord(
    Integer id,
    Integer facilityId,
    String  name,
    String  parent,
    boolean spv,
    boolean hq,
    @JsonProperty("investor_type")
    String  investorType,
    @JsonProperty("inst_vs_hnw")
    String  instVsHnw,
    @JsonProperty("region_location")
    String  regionLocation,
    boolean ig,
    String  cls,
    String  sp,
    String  mdy,
    String  fitch,
    String  aum,
    String  uc,
    String  abb,
    boolean inc,
    boolean rcl,
    boolean tf,
    String  rate,
    String  agentRate,
    String  uec,
    String  ubb,
    String  delta,
    double  uecM,
    double  ubbM,
    double  abbM,
    double  deltaM,
    double  concExcessM,
    boolean highQuality
) {
    public static ComputedLpRecord from(LpRecord lpRecord, double busaRate, double uecM, double ubbM,
                                   double abbM, double deltaM, double concExcessM) {
        String cls = lpRecord.getCls();
        boolean highQuality = "Rated Investor".equals(cls)
            || "Unrated NAV > $1Bn".equals(cls)
            || "FoF & Other > $10Bn AUM".equals(cls)
            || "Corp Pension > $5Bn Assets".equals(cls);
        return new ComputedLpRecord(
            lpRecord.getId(), lpRecord.getFacilityId(), lpRecord.getInvestorName(), lpRecord.getParent(),
            lpRecord.isSpv(), lpRecord.isHighQty(), lpRecord.getInvestorType(), lpRecord.getInstVsHnw(), lpRecord.getRegionLocation(), lpRecord.isIg(),
            lpRecord.getCls(), lpRecord.getSp(), lpRecord.getMdy(), lpRecord.getFitch(),
            lpRecord.getAum(), lpRecord.getUc(), lpRecord.getAbb(), lpRecord.isInc(), lpRecord.isRcl(), lpRecord.isTf(),
            fmt(busaRate), lpRecord.getAgentRate() != null ? lpRecord.getAgentRate() : "",
            fmtM(uecM), fmtM(ubbM), fmtM(deltaM),
            uecM, ubbM, abbM, deltaM, concExcessM, highQuality
        );
    }

    private static String fmtM(double m) {
        if (m == 0) return "$0";
        double abs = Math.abs(m);
        return (m < 0 ? "-" : "") + "$" + String.format("%.1f", abs) + "M";
    }

    private static String fmt(double r) {
        return String.format("%.0f", r * 100) + "%";
    }
}
