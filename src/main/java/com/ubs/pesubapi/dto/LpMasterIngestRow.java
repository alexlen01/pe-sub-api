package com.ubs.pesubapi.dto;

/**
 * One LP Master row from the pe-sub-jobs LP Master feed (analyst-compiled CSV).
 * Upserted by {@code investorName}: the whole profile — identity, classification, ratings,
 * financial scale, and the UBS credit-profile defaults — is replaced with the feed values,
 * matching the feed's authoritative-source semantics.
 */
public record LpMasterIngestRow(
        String investorName,
        String parent,
        boolean spv,
        boolean highQty,
        String investorType,
        String instVsHnw,
        String regionLocation,
        boolean investmentGrade,
        String sp,
        String mdy,
        String fitch,
        String aum,
        String nav,
        String pension,
        String pensionFunded,
        String ubsClassification,
        String ubsDefaultAdvRate,
        String ubsDefaultConcLimit,
        String notes) {}
