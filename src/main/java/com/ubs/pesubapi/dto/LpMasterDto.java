package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.util.MoneyValues;

import java.math.BigDecimal;

/**
 * A bank-wide LP Master profile as served to pe-sub-ui. Keys mirror {@link LpMaster} and, where the
 * same fact exists on both, {@link LpRecordDto} — so a screen can render either without a mapping
 * layer.
 *
 * <p>{@code ubsDefaultAdvanceRate} is a fraction (0.90 = 90%);
 * {@code ubsDefaultConcentrationLimit} is a formatted string because it packs a percent-or-dollars
 * distinction, exactly as the per-record limits do.
 *
 * <p>Parent/child: {@code parent} is the sponsor name as displayed and ingested, {@code parentId}
 * the resolved self-reference, and {@code ultimateParent} the top of the chain — the entity whose
 * credit profile a match against this record applies. {@code childCount} lets the LP Master Records
 * screen mark sponsors without a second round-trip.
 */
public record LpMasterDto(
        Integer id,
        String  investorName,
        String  parent,
        Integer parentId,
        boolean isUltimateParent,
        String  ultimateParent,
        int     childCount,
        boolean spv,
        boolean highQuality,
        String  investorType,
        String  institutionalOrHnw,
        String  regionLocation,
        boolean investmentGrade,
        String  ubsLpCategory,
        String  spRating,
        String  moodysRating,
        String  fitchRating,
        String  aum,
        String  nav,
        String  pensionAssets,
        BigDecimal fundingRatio,
        BigDecimal ubsDefaultAdvanceRate,
        String  ubsDefaultConcentrationLimit,
        String  notes
) {
    /** Row on its own, with no resolved hierarchy context. Used where the chain is not loaded. */
    public static LpMasterDto from(LpMaster m) {
        return from(m, null, 0);
    }

    /**
     * @param ultimateParentName top of the parent chain, or null when {@code m} is itself ultimate
     * @param childCount         direct children pointing at {@code m}
     */
    public static LpMasterDto from(LpMaster m, String ultimateParentName, int childCount) {
        return new LpMasterDto(
                m.getId(),
                m.getInvestorName(),
                m.getParent(),
                m.getParentId(),
                m.isUltimateParent(),
                ultimateParentName,
                childCount,
                m.isSpv(),
                m.isHighQuality(),
                m.getInvestorType(),
                m.getInstitutionalOrHnw(),
                m.getRegionLocation(),
                m.isInvestmentGrade(),
                m.getUbsLpCategory(),
                m.getSpRating(),
                m.getMoodysRating(),
                m.getFitchRating(),
                m.getAum(),
                m.getNav(),
                m.getPensionAssets(),
                m.getFundingRatio(),
                m.getUbsDefaultAdvanceRate(),
                MoneyValues.concLimitText(m.getUbsDefaultConcentrationLimit()),
                m.getNotes()
        );
    }
}
