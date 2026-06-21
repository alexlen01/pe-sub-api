package com.ubs.pesubapi.dto;

import java.util.List;

/**
 * Batch save of the credit officer's classification & rate decisions from the
 * "LP Classification & Rate Assignment" screen onto persisted LP Master records.
 * Rows are matched to existing records by (facilityId, name); unmatched rows are ignored.
 * Rate fields are expressed as percentages (e.g. 90.0, 7.5) and stored as decimal fractions.
 *
 * <p>The screen auto-saves each edited row individually as the user types, so those calls leave
 * {@code audit} null/false to avoid one audit entry per keystroke. A single aggregated entry is
 * written only on the final flush sent when the user leaves the screen, which sets {@code audit}
 * to {@code true}.
 */
public record LpClassificationRequest(
    Integer facilityId,
    String  effectiveDate,        // YYYY-MM; null → current month
    Boolean audit,                // true → write one aggregated audit entry; null/false → silent
    List<Row> rows
) {
    public record Row(
        String  name,
        // Identity & classification (manual)
        String  parent,
        Boolean spv,
        String  type,             // Institutional vs HNW
        Boolean ig,               // Investment Grade?
        String  agentCls,         // Agent LP Classification (verbatim from Agent BB)
        String  cls,              // UBS LP Classification (follows Agent LP Classification)
        String  sp,
        String  mdy,
        String  fitch,
        // Scale (manual)
        String  aum,              // Assets Under Management
        String  nav,              // Net Asset Value
        String  pension,          // Pension Assets
        String  pensionFunded,    // Pension Funded %
        // Commitment / capital (manual)
        String  capCommit,
        String  uc,
        // Rates & limits (manual; percentages, e.g. 90.0 / 7.5)
        Double  ubsAdvRatePct,    // null → rate left unchanged
        Double  agentRatePct,     // null → unchanged
        Double  ubsConcLimitPct,  // null → limit left unchanged
        Double  agentConcLimitPct,// null → unchanged
        // Status (manual)
        Boolean inc,
        String  notes
    ) {}
}
