package com.ubs.pesubapi.dto;

import java.util.List;

/**
 * Batch save of the credit officer's classification & rate decisions from the
 * "LP Classification & Rate Assignment" screen onto persisted LP Master records.
 * Rows are matched to existing records by (facilityId, name); unmatched rows are ignored.
 * Rate fields are expressed as percentages (e.g. 90.0, 7.5) and stored as decimal fractions.
 */
public record LpClassificationRequest(
    Integer facilityId,
    String  effectiveDate,        // YYYY-MM; null → current month
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
