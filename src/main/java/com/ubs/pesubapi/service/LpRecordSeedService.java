package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.IngestSummary;
import com.ubs.pesubapi.dto.LpRecordSeedRow;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.util.MoneyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds facility LP records from the pe-sub-jobs feed, which carries the full per-LP column
 * set of the LP DB Export (see LpRecordSeedRow). Replaces the batch job's direct SQL against
 * lp_records — pe-sub-api owns the schema, so all writes route through here.
 *
 * <p>The facility and LP Master references are resolved by name server-side. Row values are
 * authoritative; the LP Master profile (identity, ratings, financial scale) only fills fields
 * the row left blank, which keeps legacy 7-column feeds on the old merge behavior. lp_records has
 * deliberately NO unique constraint on (facility_id, investor_name) — the same investor may
 * appear across sleeves/vintages/SPVs — so idempotency is enforced in application code:
 * a pair that already has a record is skipped, never overwritten. This keeps re-seeding on
 * every pe-sub-jobs startup non-destructive to rows committed through the Shadow BB flow.
 */
@Service
public class LpRecordSeedService {

    private static final Logger log = LoggerFactory.getLogger(LpRecordSeedService.class);

    private final LpRecordRepository lpRecordRepo;
    private final LpMasterRepository lpMasterRepo;
    private final FacilityRepository facilityRepo;

    public LpRecordSeedService(LpRecordRepository lpRecordRepo,
                               LpMasterRepository lpMasterRepo,
                               FacilityRepository facilityRepo) {
        this.lpRecordRepo = lpRecordRepo;
        this.lpMasterRepo = lpMasterRepo;
        this.facilityRepo = facilityRepo;
    }

    @Transactional
    public IngestSummary seed(List<LpRecordSeedRow> rows) {
        int created = 0, skipped = 0, seq = 0;
        for (LpRecordSeedRow row : rows) {
            seq++;
            if (row == null
                    || row.facilityName() == null || row.facilityName().isBlank()
                    || row.investorName() == null || row.investorName().isBlank()) {
                skipped++;
                continue;
            }
            Facility facility = facilityRepo.findByName(row.facilityName().trim()).orElse(null);
            if (facility == null) {
                log.warn("LP record seed: facility not found — skipping row: {}", row.facilityName());
                skipped++;
                continue;
            }
            LpMaster master = lpMasterRepo.findByInvestorName(row.investorName().trim()).orElse(null);
            if (master == null) {
                log.warn("LP record seed: LP Master record not found — skipping row: {}", row.investorName());
                skipped++;
                continue;
            }
            if (lpRecordRepo.existsByFacilityIdAndInvestorName(facility.getId(), row.investorName().trim())) {
                skipped++;
                continue;
            }

            String agentCls = normalizeAgentClassification(row.agentLpCategory());
            LpRecord lp = new LpRecord();
            lp.setFacilityId(facility.getId());
            lp.setLpMasterId(master.getId());
            lp.setSourceSeq(seq);
            lp.setInvestorName(row.investorName().trim());
            // Full-column seed: the row value wins; the LP Master golden profile only fills blanks
            // (legacy 7-column feeds arrive with these fields blank and keep the old merge behavior).
            lp.setParent(coalesce(row.parent(), master.getParent()));
            lp.setSpv(boolOr(row.spv(), master.isSpv()));
            // No longer fed by the export: inherit the LP Master value, which is itself on the
            // schema default (TRUE) unless an analyst has set it.
            lp.setHighQuality(master.isHighQuality());
            lp.setInvestorType(coalesce(row.investorType(), coalesce(master.getInvestorType(), "")));
            lp.setInstitutionalOrHnw(coalesce(row.institutionalOrHnw(), coalesce(master.getInstitutionalOrHnw(), "Institutional")));
            lp.setRegionLocation(coalesce(row.regionLocation(), coalesce(master.getRegionLocation(), "")));
            lp.setInvestmentGrade(boolOr(row.investmentGrade(), master.isInvestmentGrade()));
            lp.setUbsLpCategory(normalizeUbsClassification(
                    coalesce(row.ubsLpCategory(), master.getUbsLpCategory()), agentCls));
            lp.setAgentLpCategory(agentCls);
            lp.setAgentLpCategorySource("EXTRACTED");
            lp.setSpRating(coalesce(row.spRating(), coalesce(master.getSpRating(), "")));
            lp.setMoodysRating(coalesce(row.moodysRating(), coalesce(master.getMoodysRating(), "")));
            lp.setFitchRating(coalesce(row.fitchRating(), coalesce(master.getFitchRating(), "")));
            lp.setAum(coalesce(row.aum(), master.getAum()));
            lp.setNav(coalesce(row.nav(), master.getNav()));
            lp.setPensionAssets(coalesce(row.pensionAssets(), master.getPensionAssets()));
            BigDecimal fundingRatio = MoneyValues.fraction(row.fundingRatio());
            lp.setFundingRatio(fundingRatio != null ? fundingRatio : master.getFundingRatio());
            lp.setCapitalCommitment(MoneyValues.dollars(row.capitalCommitment()));
            lp.setUncalledCapital(MoneyValues.dollars(row.uncalledCapital()));
            lp.setAgentAdvanceRate(MoneyValues.fraction(row.agentAdvanceRate()));
            lp.setAgentConcentrationLimit(MoneyValues.decimal(row.agentConcentrationLimit()));
            // Row-only fields (no LP Master counterpart):
            lp.setPctOfFundCommitments(MoneyValues.fraction(row.pctOfFundCommitments()));
            lp.setCalledCapital(MoneyValues.dollars(row.calledCapital()));
            lp.setPctOfFundUncalled(MoneyValues.fraction(row.pctOfFundUncalled()));
            lp.setPctLpCalled(MoneyValues.fraction(row.pctLpCalled()));
            lp.setUbsConcentrationLimit(MoneyValues.concLimit(row.ubsConcentrationLimit()));
            lp.setUbsAdvanceRate(MoneyValues.fraction(row.ubsAdvanceRate()));
            lp.setAgentExcessConcentration(MoneyValues.dollars(row.agentExcessConcentration()));
            lp.setUbsExcessConcentration(MoneyValues.dollars(row.ubsExcessConcentration()));
            lp.setAgentBorrowingBase(MoneyValues.dollars(row.agentBorrowingBase()));
            lp.setUbsBorrowingBase(MoneyValues.dollars(row.ubsBorrowingBase()));
            lp.setNotes(row.notes());
            lp.setIncluded(true);
            lp.setReclassified(false);
            lp.setTransferee(false);
            lpRecordRepo.save(lp);
            created++;
        }
        return new IngestSummary(created, 0, skipped);
    }

    private static String coalesce(String v, String fallback) {
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    /** "TRUE"/"FALSE" feed string -> boolean; blank/absent falls back to the LP Master value. */
    private static boolean boolOr(String v, boolean fallback) {
        return (v != null && !v.isBlank()) ? v.trim().equalsIgnoreCase("true") : fallback;
    }

    private static String normalizeAgentClassification(String raw) {
        if (raw == null || raw.isBlank()) return "Non-Rated Included";
        return switch (raw.trim()) {
            case "Rated", "Rated Included" -> "Rated Included";
            case "Non-Rated", "Non-Rated Included" -> "Non-Rated Included";
            case "Designated", "Designated Institutional" -> "Designated Institutional";
            case "Designated PWM" -> "Designated PWM";
            case "Ineligible", "Ineligible Investor", "Ineligible Investors" -> "Ineligible Investor";
            default -> raw.trim();
        };
    }

    private static String normalizeUbsClassification(String raw, String agentCls) {
        String value = raw == null ? "" : raw.trim();
        return switch (value) {
            // Canonical 9-class taxonomy (classification_config UBS_CLS_OPTS) passes through.
            case "Rated Investor" -> "Rated Investor";
            case "Unrated NAV > $1Bn" -> "Unrated NAV > $1Bn";
            case "FoF & Other > $10Bn AUM" -> "FoF & Other > $10Bn AUM";
            case "Corp Pension > $5Bn Assets" -> "Corp Pension > $5Bn Assets";
            case "Corp Pension > $1Bn Assets" -> "Corp Pension > $1Bn Assets";
            case "Other Institutional" -> "Other Institutional";
            case "HNW Feeder (acceptable)" -> "HNW Feeder (acceptable)";
            case "HNW (acceptable)" -> "HNW (acceptable)";
            case "Excluded" -> "Excluded";
            // Legacy feed labels.
            case "Rated", "Rated Included" -> "Rated Investor";
            case "Unrated >2bn", "Unrated AUM >$2bn", "Unrated AUM $1-2bn", "Eligible <$1bn",
                 "Non-Rated", "Non-Rated Included" -> "Unrated NAV > $1Bn";
            case "Designated Institutional" -> "Corp Pension > $5Bn Assets";
            case "Eligible", "Included (PWM)" -> "Other Institutional";
            case "Designated PWM" -> "HNW Feeder (acceptable)";
            case "Ineligible Investor", "Ineligible Investors" -> "Excluded";
            // Unknown/blank: fall back to the agent category (AGENT_CLS_UBS_MAP).
            default -> switch (agentCls) {
                case "Rated Included" -> "Rated Investor";
                case "Designated Institutional" -> "Corp Pension > $5Bn Assets";
                case "Designated PWM" -> "HNW Feeder (acceptable)";
                case "Ineligible Investor" -> "Excluded";
                default -> "Unrated NAV > $1Bn";
            };
        };
    }
}
