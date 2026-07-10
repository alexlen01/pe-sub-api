package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.LpClassificationRequest;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.entity.LpRate;
import com.ubs.pesubapi.repository.LpRateRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.util.EffectivePeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
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

    public LpClassificationService(LpRecordRepository lpRecordRepo, LpRateRepository rateRepo,
                                   LpMasterWriteBackService lpMasterWriteBack) {
        this.lpRecordRepo   = lpRecordRepo;
        this.rateRepo = rateRepo;
        this.lpMasterWriteBack = lpMasterWriteBack;
    }

    @Transactional
    public int applyClassifications(LpClassificationRequest req) {
        if (req.facilityId() == null || req.rows() == null) return 0;
        LocalDate effectiveDate = parseMonth(req.effectiveDate());

        Map<String, LpRecord> byName = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(req.facilityId()).stream()
            .collect(Collectors.toMap(lpRecord -> lpRecord.getInvestorName(), lpRecord -> lpRecord, (a, b) -> a));

        int updated = 0;
        for (LpClassificationRequest.Row row : req.rows()) {
            if (row.name() == null) continue;
            String lookupName = row.originalName() != null && !row.originalName().isBlank()
                ? row.originalName()
                : row.name();
            LpRecord lpRecord = byName.get(lookupName);
            if (lpRecord == null) continue;   // only persisted LP Master records are updated

            if (row.name() != null && !row.name().isBlank()) lpRecord.setInvestorName(row.name());
            if (row.parent()         != null) lpRecord.setParent(row.parent());
            if (row.spv()            != null) lpRecord.setSpv(row.spv());
            if (row.fundSleeve()     != null) lpRecord.setFundSleeve(row.fundSleeve());
            if (row.investorType()  != null) lpRecord.setInvestorType(row.investorType());
            if (row.instVsHnw()     != null) lpRecord.setInstVsHnw(row.instVsHnw());
            if (row.regionLocation() != null) lpRecord.setRegionLocation(row.regionLocation());
            if (row.ig()             != null) lpRecord.setIg(row.ig());
            if (row.cls()            != null) lpRecord.setCls(row.cls());
            if (row.agentCls()       != null) {
                lpRecord.setAgentCls(row.agentCls());
                lpRecord.setAgentClsSource(normalizeAgentClsSource(row.agentClsSource(), "USER_EDITED"));
            }
            if (row.sp()             != null) lpRecord.setSp(row.sp());
            if (row.mdy()            != null) lpRecord.setMdy(row.mdy());
            if (row.fitch()          != null) lpRecord.setFitch(row.fitch());
            if (row.aum()            != null) lpRecord.setAum(row.aum());
            if (row.nav()            != null) lpRecord.setNav(row.nav());
            if (row.pension()        != null) lpRecord.setPension(row.pension());
            if (row.pensionFunded()  != null) lpRecord.setPensionFunded(row.pensionFunded());
            if (row.capCommit()      != null) lpRecord.setCapCommit(row.capCommit());
            if (row.inc()            != null) lpRecord.setInc(row.inc());
            if (row.tf()             != null) lpRecord.setTf(row.tf());
            if (row.uc()             != null) lpRecord.setUc(row.uc());
            if (row.notes()          != null) lpRecord.setNotes(row.notes());
            // Rates: persist the display strings on the lpRecord, and upsert the decimal fractions
            // into lp_rates below for the submission period.
            if (row.ubsAdvRatePct()  != null) lpRecord.setUbsRate(formatPct(row.ubsAdvRatePct()));
            if (row.ubsConcLimitPct() != null) lpRecord.setUbsConc(formatPct(row.ubsConcLimitPct()));
            if (row.agentRatePct()   != null) lpRecord.setAgentRate(formatPct(row.agentRatePct()));
            if (row.agentConcLimitPct() != null) lpRecord.setAgentConc(formatPct(row.agentConcLimitPct()));
            lpRecord.setUpdatedAt(LocalDateTime.now());
            lpRecordRepo.save(lpRecord);
            updated++;

            if (row.ubsAdvRatePct() != null || row.ubsConcLimitPct() != null) {
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
        rate.setClassification(row.cls() != null ? row.cls()
            : (lpRecord.getCls() != null ? lpRecord.getCls() : ""));
        if (row.ubsAdvRatePct() != null) {
            rate.setUbsAdvRatePct(toFraction(row.ubsAdvRatePct()));
        } else if (rate.getUbsAdvRatePct() == null) {
            rate.setUbsAdvRatePct(BigDecimal.ZERO);
        }
        if (row.ubsConcLimitPct() != null) {
            rate.setUbsConcLimitPct(toFraction(row.ubsConcLimitPct()));
        } else if (rate.getUbsConcLimitPct() == null) {
            rate.setUbsConcLimitPct(BigDecimal.ZERO);
        }
        rate.setSource("SHADOW_BB");
        rateRepo.save(rate);
    }

    /** Percentage (90.0) → decimal fraction (0.9000) as stored in lp_rates. */
    private BigDecimal toFraction(double pct) {
        return BigDecimal.valueOf(pct).movePointLeft(2);
    }

    /** Percentage (90.0) → display string ("90%") as stored on the LP record. */
    private String formatPct(double pct) {
        return (pct == Math.rint(pct) ? String.valueOf((long) pct) : String.valueOf(pct)) + "%";
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
}
