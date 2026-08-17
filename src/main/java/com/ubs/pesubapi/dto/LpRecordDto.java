package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.util.MoneyValues;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An LP record as served to pe-sub-ui. Keys mirror the {@link LpRecord} field names one-for-one and
 * are camelCase throughout — no snake_case aliases.
 *
 * <p>Two representations coexist deliberately:
 * <ul>
 *   <li>Money and the concentration limits are pre-formatted display strings
 *       ({@code "$12,000,000"}, {@code "7.5%"}), because the limits pack a percent-or-dollars
 *       distinction the client should not have to re-derive. {@code aum} is stored compact or
 *       verbatim ({@code "$4.2B"}) and expanded here, once, to the same full-precision form;
 *       {@code nav} and {@code pensionAssets} remain verbatim passthrough.</li>
 *   <li>Percents and advance rates are raw fractions (0.91 = 91%), formatted by the client.</li>
 * </ul>
 */
public record LpRecordDto(
    Integer       id,
    Integer       facilityId,
    String        investorName,
    String        parent,
    boolean       spv,
    boolean       highQuality,
    String        investorType,
    String        institutionalOrHnw,
    String        regionLocation,
    boolean       investmentGrade,
    String        agentLpCategory,
    String        agentLpCategorySource,
    String        ubsLpCategory,
    String        ubsLpCategoryTag,
    String        spRating,
    String        moodysRating,
    String        fitchRating,
    String        aum,
    String        nav,
    String        pensionAssets,
    BigDecimal    fundingRatio,
    String        capitalCommitment,
    BigDecimal    pctOfFundCommitments,
    String        calledCapital,
    String        uncalledCapital,
    BigDecimal    pctOfFundUncalled,
    BigDecimal    pctLpCalled,
    String        agentConcentrationLimit,
    String        ubsConcentrationLimit,
    BigDecimal    ubsAdvanceRate,
    BigDecimal    agentAdvanceRate,
    String        agentExcessConcentration,
    String        ubsExcessConcentration,
    String        agentBorrowingBase,
    String        ubsBorrowingBase,
    boolean       included,
    boolean       reclassified,
    boolean       transferee,
    Integer       lpRank,
    String        notes,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static LpRecordDto from(LpRecord lpRecord) {
        return new LpRecordDto(
            lpRecord.getId(), lpRecord.getFacilityId(), lpRecord.getInvestorName(), lpRecord.getParent(),
            lpRecord.isSpv(), lpRecord.isHighQuality(), lpRecord.getInvestorType(),
            lpRecord.getInstitutionalOrHnw(), lpRecord.getRegionLocation(), lpRecord.isInvestmentGrade(),
            lpRecord.getAgentLpCategory(), lpRecord.getAgentLpCategorySource(),
            lpRecord.getUbsLpCategory(), lpRecord.getUbsLpCategoryTag(),
            lpRecord.getSpRating(), lpRecord.getMoodysRating(), lpRecord.getFitchRating(),
            MoneyValues.expand(lpRecord.getAum()), lpRecord.getNav(),
            lpRecord.getPensionAssets(), lpRecord.getFundingRatio(),
            MoneyValues.display(lpRecord.getCapitalCommitment()), lpRecord.getPctOfFundCommitments(),
            MoneyValues.display(lpRecord.getCalledCapital()),
            MoneyValues.display(lpRecord.getUncalledCapital()), lpRecord.getPctOfFundUncalled(),
            lpRecord.getPctLpCalled(),
            MoneyValues.concLimitText(lpRecord.getAgentConcentrationLimit()),
            MoneyValues.concLimitText(lpRecord.getUbsConcentrationLimit()),
            lpRecord.getUbsAdvanceRate(), lpRecord.getAgentAdvanceRate(),
            MoneyValues.display(lpRecord.getAgentExcessConcentration()), MoneyValues.display(lpRecord.getUbsExcessConcentration()),
            MoneyValues.display(lpRecord.getAgentBorrowingBase()), MoneyValues.display(lpRecord.getUbsBorrowingBase()),
            lpRecord.isIncluded(), lpRecord.isReclassified(), lpRecord.isTransferee(),
            lpRecord.getLpRank(), lpRecord.getNotes(),
            lpRecord.getCreatedAt(), lpRecord.getUpdatedAt());
    }
}
