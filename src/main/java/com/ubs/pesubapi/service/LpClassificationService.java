package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.LpClassificationRequest;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.entity.LpRate;
import com.ubs.pesubapi.repository.LpRateRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.util.EffectivePeriod;
import com.ubs.pesubapi.util.MoneyValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Materialises the classification & rate edits made on the "LP Category & Rate Assignment"
 * screen onto persisted LP Master records. This is what the screen's "Save" button calls: it writes
 * to the real LP records (created earlier on Commit Decisions), not to a draft override blob.
 * LP entity fields (cls, ratings, inclusion, uncalled) update in place; advance rate and
 * concentration limit upsert into lp_rates for the submission period.
 */
@Service
public class LpClassificationService {

    private final LpRecordRepository     lpRecordRepo;
    private final LpRateRepository rateRepo;
    private final LpMasterWriteBackService lpMasterWriteBack;
    private final ReclassificationPolicy reclassificationPolicy;

    public LpClassificationService(LpRecordRepository lpRecordRepo, LpRateRepository rateRepo,
                                   LpMasterWriteBackService lpMasterWriteBack,
                                   ReclassificationPolicy reclassificationPolicy) {
        this.lpRecordRepo   = lpRecordRepo;
        this.rateRepo = rateRepo;
        this.lpMasterWriteBack = lpMasterWriteBack;
        this.reclassificationPolicy = reclassificationPolicy;
    }

    @Transactional
    public int applyClassifications(LpClassificationRequest req) {
        if (req.facilityId() == null || req.rows() == null) return 0;
        LocalDate effectiveDate = parseMonth(req.effectiveDate());
        // Wizard steps 1–5 run before the submission's first Shadow BB, so category edits there are
        // initial data entry rather than reclassifications. See ReclassificationPolicy.
        boolean marksReclassification = reclassificationPolicy.marksReclassification(req.facilityId());

        Map<Integer, LpRecord> byId = new HashMap<>();
        lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(req.facilityId()).forEach(lpRecord -> {
            if (lpRecord.getId() != null) {
                byId.putIfAbsent(lpRecord.getId(), lpRecord);
            }
        });
        Map<String, LpRecord> byName = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(req.facilityId()).stream()
            .collect(Collectors.toMap(lpRecord -> lpRecord.getInvestorName(), lpRecord -> lpRecord, (a, b) -> a));

        int updated = 0;
        for (LpClassificationRequest.Row row : req.rows()) {
            if (row.investorName() == null) continue;
            LpRecord lpRecord = row.id() != null ? byId.get(row.id()) : null;
            if (lpRecord == null) {
                String lookupName = row.originalName() != null && !row.originalName().isBlank()
                    ? row.originalName()
                    : row.investorName();
                lpRecord = byName.get(lookupName);
            }
            if (lpRecord == null) continue;   // only persisted LP Master records are updated

            // Derive reclassification from persisted values during Save. The flag is sticky so
            // downstream screens and reports do not depend on a separate client-side action.
            boolean reclassified = marksReclassification
                && (changed(row.agentLpCategory(), lpRecord.getAgentLpCategory())
                    || changed(row.ubsLpCategory(), lpRecord.getUbsLpCategory()));

            if (row.investorName() != null && !row.investorName().isBlank()) lpRecord.setInvestorName(row.investorName());
            if (row.parent()         != null) lpRecord.setParent(row.parent());
            if (row.spv()            != null) lpRecord.setSpv(row.spv());
            if (row.investorType()  != null) lpRecord.setInvestorType(row.investorType());
            if (row.institutionalOrHnw()     != null) lpRecord.setInstitutionalOrHnw(row.institutionalOrHnw());
            if (row.regionLocation() != null) lpRecord.setRegionLocation(row.regionLocation());
            if (row.investmentGrade()             != null) lpRecord.setInvestmentGrade(row.investmentGrade());
            if (row.ubsLpCategory()            != null) lpRecord.setUbsLpCategory(row.ubsLpCategory());
            if (row.agentLpCategory()       != null) {
                lpRecord.setAgentLpCategory(row.agentLpCategory());
                lpRecord.setAgentLpCategorySource(normalizeAgentClsSource(row.agentLpCategorySource(), "USER_EDITED"));
            }
            if (row.spRating()             != null) lpRecord.setSpRating(row.spRating());
            if (row.moodysRating()            != null) lpRecord.setMoodysRating(row.moodysRating());
            if (row.fitchRating()          != null) lpRecord.setFitchRating(row.fitchRating());
            if (row.aum()            != null) lpRecord.setAum(row.aum());
            if (row.nav()            != null) lpRecord.setNav(row.nav());
            if (row.pensionAssets()  != null) lpRecord.setPensionAssets(row.pensionAssets());
            if (row.fundingRatio()   != null) lpRecord.setFundingRatio(MoneyValues.ratio(row.fundingRatio()));
            if (row.capitalCommitment()      != null) lpRecord.setCapitalCommitment(MoneyValues.dollars(row.capitalCommitment()));
            if (row.included()            != null) lpRecord.setIncluded(row.included());
            if (row.transferee()             != null) lpRecord.setTransferee(row.transferee());
            if (row.uncalledCapital()             != null) lpRecord.setUncalledCapital(MoneyValues.dollars(row.uncalledCapital()));
            if (row.notes()          != null) lpRecord.setNotes(row.notes());
            if (reclassified) lpRecord.setReclassified(true);
            // Rates arrive percent-scaled (90.0 = 90%) and persist as fractions, matching lp_rates
            // below. The concentration limits are the exception: they keep the inbound percent scale
            // so the magnitude split against an absolute dollar cap still holds.
            if (row.ubsAdvanceRatePct()  != null) lpRecord.setUbsAdvanceRate(toFraction(row.ubsAdvanceRatePct()));
            if (row.ubsConcentrationLimitPct() != null) lpRecord.setUbsConcentrationLimit(BigDecimal.valueOf(row.ubsConcentrationLimitPct()));
            if (row.agentAdvanceRatePct()   != null) lpRecord.setAgentAdvanceRate(toFraction(row.agentAdvanceRatePct()));
            if (row.agentConcentrationLimitPct() != null) lpRecord.setAgentConcentrationLimit(BigDecimal.valueOf(row.agentConcentrationLimitPct()));
            lpRecord.setUpdatedAt(LocalDateTime.now());
            lpRecordRepo.save(lpRecord);
            updated++;

            if (row.ubsAdvanceRatePct() != null || row.ubsConcentrationLimitPct() != null) {
                upsertRate(lpRecord, effectiveDate, row);
            }
        }

        // Propagate the manual edits to the bank-wide LP Master. The screen auto-saves each row as
        // the user types (silent, high-frequency); only the aggregated flush sent on leaving the
        // screen carries audit=true. Gating the (facility-wide) write-back on that flush keeps a
        // single LP Master sync per editing session rather than one per keystroke.
        if (updated > 0 && Boolean.TRUE.equals(req.audit())) {
            lpMasterWriteBack.writeBack(req.facilityId());
        }
        return updated;
    }

    private void upsertRate(LpRecord lpRecord, LocalDate effectiveDate, LpClassificationRequest.Row row) {
        LpRate rate = rateRepo.findByLpIdAndEffectiveDate(lpRecord.getId(), effectiveDate)
            .orElseGet(LpRate::new);
        rate.setLpId(lpRecord.getId());
        rate.setEffectiveDate(effectiveDate);
        rate.setClassification(row.ubsLpCategory() != null ? row.ubsLpCategory()
            : (lpRecord.getUbsLpCategory() != null ? lpRecord.getUbsLpCategory() : ""));
        if (row.ubsAdvanceRatePct() != null) {
            rate.setUbsAdvRatePct(toFraction(row.ubsAdvanceRatePct()));
        } else if (rate.getUbsAdvRatePct() == null) {
            rate.setUbsAdvRatePct(BigDecimal.ZERO);
        }
        if (row.ubsConcentrationLimitPct() != null) {
            rate.setUbsConcLimitPct(toFraction(row.ubsConcentrationLimitPct()));
        } else if (rate.getUbsConcLimitPct() == null) {
            rate.setUbsConcLimitPct(BigDecimal.ZERO);
        }
        rate.setSource("SHADOW_BB");
        rateRepo.save(rate);
    }

    /** Percentage (90.0) → decimal fraction (0.9000) as stored on lp_records and in lp_rates. */
    private BigDecimal toFraction(double pct) {
        return BigDecimal.valueOf(pct).movePointLeft(2);
    }

    /** Submission period → first of the month; null/blank defaults to the current month. */
    private String normalizeAgentClsSource(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "EXTRACTED", "DERIVED", "USER_EDITED" -> value;
            default -> fallback;
        };
    }

    private LocalDate parseMonth(String ym) {
        if (ym == null || ym.isBlank()) return LocalDate.now().withDayOfMonth(1);
        return EffectivePeriod.firstOfMonth(ym);
    }

    private boolean changed(String requested, String persisted) {
        return requested != null && !Objects.equals(normalize(requested), normalize(persisted));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
