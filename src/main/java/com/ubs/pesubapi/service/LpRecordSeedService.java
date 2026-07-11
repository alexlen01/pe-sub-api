package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.IngestSummary;
import com.ubs.pesubapi.dto.LpRecordSeedRow;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds facility LP records from the pe-sub-jobs feed (facility name + investor name +
 * commitment terms). Replaces the batch job's direct SQL against lp_records — pe-sub-api owns
 * the schema, so all writes route through here.
 *
 * <p>The facility and LP Master references are resolved by name server-side and the LP Master
 * profile (identity, ratings, financial scale) is merged onto the new record. lp_records has
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

            String agentCls = normalizeAgentClassification(row.agentCls());
            LpRecord lp = new LpRecord();
            lp.setFacilityId(facility.getId());
            lp.setLpMasterId(master.getId());
            lp.setSourceSeq(seq);
            lp.setInvestorName(row.investorName().trim());
            lp.setParent(master.getParent());
            lp.setSpv(master.isSpv());
            lp.setHighQty(master.isHighQty());
            lp.setInvestorType(coalesce(master.getInvestorType(), ""));
            lp.setInstVsHnw(coalesce(master.getInstVsHnw(), "Institutional"));
            lp.setRegionLocation(coalesce(master.getRegionLocation(), ""));
            lp.setIg(master.isIg());
            lp.setCls(normalizeUbsClassification(master.getUbsClassification(), agentCls));
            lp.setAgentCls(agentCls);
            lp.setAgentClsSource("EXTRACTED");
            lp.setSp(coalesce(master.getSp(), ""));
            lp.setMdy(coalesce(master.getMdy(), ""));
            lp.setFitch(coalesce(master.getFitch(), ""));
            lp.setAum(master.getAum());
            lp.setNav(master.getNav());
            lp.setPension(master.getPension());
            lp.setPensionFunded(master.getPensionFunded());
            lp.setCapCommit(row.capCommit());
            lp.setUc(row.uncalled());
            lp.setAgentRate(row.agentRate());
            lp.setAgentConc(row.agentConc());
            lp.setInc(true);
            lp.setRcl(false);
            lp.setTf(false);
            lpRecordRepo.save(lp);
            created++;
        }
        return new IngestSummary(created, 0, skipped);
    }

    private static String coalesce(String v, String fallback) {
        return (v != null && !v.isBlank()) ? v : fallback;
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
            case "Rated Investor" -> "Rated Investor";
            case "Unrated NAV > $1Bn" -> "Unrated NAV > $1Bn";
            case "FoF & Other > $10Bn AUM" -> "FoF & Other > $10Bn AUM";
            case "Corp Pension > $5Bn Assets" -> "Corp Pension > $5Bn Assets";
            case "Other Institutional" -> "Other Institutional";
            case "Excluded" -> "Excluded";
            case "Rated", "Rated Included" -> "Rated Investor";
            case "Unrated >2bn", "Unrated AUM >$2bn", "Unrated AUM $1-2bn", "Eligible <$1bn",
                 "Non-Rated", "Non-Rated Included" -> "Unrated NAV > $1Bn";
            case "Designated Institutional" -> "Corp Pension > $5Bn Assets";
            case "Eligible", "Designated PWM", "Included (PWM)" -> "Other Institutional";
            case "Ineligible Investor", "Ineligible Investors" -> "Excluded";
            default -> switch (agentCls) {
                case "Rated Included" -> "Rated Investor";
                case "Designated Institutional" -> "Corp Pension > $5Bn Assets";
                case "Designated PWM" -> "Other Institutional";
                case "Ineligible Investor" -> "Excluded";
                default -> "Unrated NAV > $1Bn";
            };
        };
    }
}
