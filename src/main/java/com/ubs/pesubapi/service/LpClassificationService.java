package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.LpClassificationRequest;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.entity.LpRate;
import com.ubs.pesubapi.repository.LpRateRepository;
import com.ubs.pesubapi.repository.LpRepository;
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

    private final LpRepository     lpRepo;
    private final LpRateRepository rateRepo;

    public LpClassificationService(LpRepository lpRepo, LpRateRepository rateRepo) {
        this.lpRepo   = lpRepo;
        this.rateRepo = rateRepo;
    }

    @Transactional
    public int applyClassifications(LpClassificationRequest req) {
        if (req.facilityId() == null || req.rows() == null) return 0;
        LocalDate effectiveDate = parseMonth(req.effectiveDate());

        Map<String, Lp> byName = lpRepo.findByFacilityIdOrderByInvestorNameAsc(req.facilityId()).stream()
            .collect(Collectors.toMap(Lp::getInvestorName, lp -> lp, (a, b) -> a));

        int updated = 0;
        for (LpClassificationRequest.Row row : req.rows()) {
            if (row.name() == null) continue;
            String lookupName = row.originalName() != null && !row.originalName().isBlank()
                ? row.originalName()
                : row.name();
            Lp lp = byName.get(lookupName);
            if (lp == null) continue;   // only persisted LP Master records are updated

            if (row.name() != null && !row.name().isBlank()) lp.setInvestorName(row.name());
            if (row.parent()         != null) lp.setParent(row.parent());
            if (row.spv()            != null) lp.setSpv(row.spv());
            if (row.type()           != null) lp.setInvType(row.type());
            if (row.ig()             != null) lp.setIg(row.ig());
            if (row.cls()            != null) lp.setCls(row.cls());
            if (row.agentCls()       != null) lp.setAgentCls(row.agentCls());
            if (row.sp()             != null) lp.setSp(row.sp());
            if (row.mdy()            != null) lp.setMdy(row.mdy());
            if (row.fitch()          != null) lp.setFitch(row.fitch());
            if (row.aum()            != null) lp.setAum(row.aum());
            if (row.nav()            != null) lp.setNav(row.nav());
            if (row.pension()        != null) lp.setPension(row.pension());
            if (row.pensionFunded()  != null) lp.setPensionFunded(row.pensionFunded());
            if (row.capCommit()      != null) lp.setCapCommit(row.capCommit());
            if (row.inc()            != null) lp.setInc(row.inc());
            if (row.uc()             != null) lp.setUc(row.uc());
            if (row.notes()          != null) lp.setNotes(row.notes());
            // Rates: persist the display strings on the LP, and upsert the decimal fractions
            // into lp_rates below for the submission period.
            if (row.ubsAdvRatePct()  != null) lp.setUbsRate(formatPct(row.ubsAdvRatePct()));
            if (row.agentRatePct()   != null) lp.setAgentRate(formatPct(row.agentRatePct()));
            if (row.agentConcLimitPct() != null) lp.setAgentConc(formatPct(row.agentConcLimitPct()));
            lp.setUpdatedAt(LocalDateTime.now());
            lpRepo.save(lp);
            updated++;

            if (row.ubsAdvRatePct() != null || row.ubsConcLimitPct() != null) {
                upsertRate(lp, effectiveDate, row);
            }
        }
        return updated;
    }

    private void upsertRate(Lp lp, LocalDate effectiveDate, LpClassificationRequest.Row row) {
        LpRate rate = rateRepo.findByLpIdAndEffectiveDate(lp.getId(), effectiveDate)
            .orElseGet(LpRate::new);
        rate.setLpId(lp.getId());
        rate.setEffectiveDate(effectiveDate);
        rate.setClassification(row.cls() != null ? row.cls()
            : (lp.getCls() != null ? lp.getCls() : "Eligible"));
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
    private LocalDate parseMonth(String ym) {
        if (ym == null || ym.isBlank()) return LocalDate.now().withDayOfMonth(1);
        return EffectivePeriod.firstOfMonth(ym);
    }
}
