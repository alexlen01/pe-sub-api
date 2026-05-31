package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.Lp;

public record ComputedLp(
    Integer id,
    Integer facilityId,
    Integer rank,
    String  name,
    String  parent,
    boolean spv,
    boolean hq,
    String  type,
    String  region,
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
    // Computed
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
    public static ComputedLp from(Lp lp, double busaRate, double uecM, double ubbM,
                                   double abbM, double deltaM, double concExcessM) {
        boolean highQuality = lp.getCls().equals("Rated")
            || lp.getCls().equals("Unrated >2bn")
            || lp.getCls().equals("Unrated 1–2bn");
        return new ComputedLp(
            lp.getId(), lp.getFacilityId(), lp.getRank(), lp.getName(), lp.getParent(),
            lp.isSpv(), lp.isHq(), lp.getType(), lp.getRegion(), lp.isIg(),
            lp.getCls(), lp.getSp(), lp.getMdy(), lp.getFitch(),
            lp.getAum(), lp.getUc(), lp.getAbb(), lp.isInc(), lp.isRcl(), lp.isTf(),
            fmt(busaRate), lp.getAgentRate() != null ? lp.getAgentRate() : "",
            fmtM(uecM), fmtM(ubbM), fmtM(deltaM),
            uecM, ubbM, abbM, deltaM, concExcessM, highQuality
        );
    }

    private static String fmtM(double m) {
        if (m == 0) return "$0";
        double abs = Math.abs(m);
        return (m < 0 ? "–" : "") + "$" + String.format("%.1f", abs) + "M";
    }

    private static String fmt(double r) {
        return String.format("%.0f", r * 100) + "%";
    }
}
