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
        String  cls,
        String  sp,
        String  mdy,
        String  fitch,
        Boolean inc,
        String  uc,
        Double  ubsAdvRatePct,    // percent, e.g. 90.0; null → rate left unchanged
        Double  ubsConcLimitPct   // percent, e.g. 7.5;  null → limit left unchanged
    ) {}
}
