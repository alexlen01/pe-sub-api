package com.ubs.pesubapi.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * The editable subset of an LP Master record, as submitted by the LP Master Records panel.
 *
 * <p>A full replace of that subset rather than a sparse patch: the panel always submits every field
 * it renders, so a null here means "clear this value", never "leave it alone". Server-managed facts
 * (id, timestamps, {@code isUltimateParent}) are not accepted from the client.
 *
 * <p>Parent linkage accepts either side of the pair. {@code parentId} wins when present;
 * {@code parent} alone is resolved against {@code investor_name} and links when it matches a row,
 * so a name typed before its sponsor exists is retained as a display value and links on the next
 * save once the sponsor is created.
 */
public record LpMasterUpdateRequest(
        @NotBlank String investorName,
        String  parent,
        Integer parentId,
        boolean spv,
        boolean highQuality,
        boolean investmentGrade,
        String  investorType,
        String  institutionalOrHnw,
        String  regionLocation,
        String  ubsLpCategory,
        String  spRating,
        String  moodysRating,
        String  fitchRating,
        String  aum,
        String  nav,
        String  pensionAssets,
        BigDecimal fundingRatio,
        BigDecimal ubsDefaultAdvanceRate,
        /** Percent-or-dollars limit as display text ("7.5%", "$25,000,000"), like the per-record limits. */
        String  ubsDefaultConcentrationLimit,
        String  notes
) {}
