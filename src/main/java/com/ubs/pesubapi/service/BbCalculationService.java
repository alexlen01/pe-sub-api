package com.ubs.pesubapi.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import com.ubs.pesubapi.dto.BbBreach;
import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.dto.BbSummary;
import com.ubs.pesubapi.dto.ComputedLpRecord;
import com.ubs.pesubapi.entity.LpRecord;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Port of pe-sub-common/src/engine/calculator.ts.
 * Produces identical numbers to the TypeScript version.
 */
@Service
public class BbCalculationService {

    private final ConfigService configService;
    private final BbCriteriaResolver criteriaResolver;

    public BbCalculationService(ConfigService configService, BbCriteriaResolver criteriaResolver) {
        this.configService = configService;
        this.criteriaResolver = criteriaResolver;
    }

    // The UBS Advance Rate is an independent manual-input column on the Shadow BB: the same class
    // may appear at 0.90 / 0.75 / 0.65 / 0.00 across the population, so the stored per-LP rate
    // takes precedence over the computed default. The workbook (bb_criteria_matrix) derives that
    // default from (classification, rating band, funded %). No legacy flat-map fallback: a class the
    // matrix does not carry, with no stored rate, is 0%. Mirrors the frontend advanceRateFraction.
    public double advanceRateFraction(LpRecord lpRecord) {
        return advanceRateFraction(lpRecord, resolveCriteria(lpRecord));
    }

    /** Advance rate (fraction) with the LP's Borrowing Base Criteria already resolved, so the
     *  {@code compute} loop resolves the matrix once per LP rather than twice. */
    double advanceRateFraction(LpRecord lpRecord, Optional<BbCriteriaResolver.Criteria> criteria) {
        double stored = parsePctDecimal(lpRecord.getUbsAdvanceRate());
        if (stored >= 0) return stored;
        return criteria.map(c -> c.advanceRatePct() / 100.0).orElse(0.0);
    }

    private Optional<BbCriteriaResolver.Criteria> resolveCriteria(LpRecord lpRecord) {
        return criteriaResolver.resolve(lpRecord.getUbsLpCategory(), lpRecord.getSpRating(), lpRecord.getMoodysRating(),
            lpRecord.getFitchRating(), pctFunded(lpRecord));
    }

    /** LP funded fraction (called ÷ commitment): stored Called Capital when present, else the
     *  derived Capital Commitments − Uncalled Capital. 0 when commitment is unavailable. */
    private static double pctFunded(LpRecord lpRecord) {
        double commitM = (lpRecord.getCapitalCommitment() != null ? lpRecord.getCapitalCommitment().doubleValue() / 1_000_000.0 : 0);
        if (commitM <= 0) return 0;
        double calledM = lpRecord.getCalledCapital() != null
            ? dollarM(lpRecord.getCalledCapital())
            : Math.max(0, commitM - dollarM(lpRecord.getUncalledCapital()));
        return calledM / commitM;
    }

    /**
     * Magnitude at or above which a bare number is read as absolute dollars rather than as a
     * percentage or a $millions figure — the same threshold {@link #parseMoney} applies to
     * suffix-less strings, and the TS reference (parseM in bbCalculationService.ts) to its inputs.
     * Concentration limits rely on it to tell a 25% limit from a $25,000,000 one.
     */
    public static final double ABSOLUTE_DOLLAR_MIN = 100_000;

    /** NUMERIC money column (absolute dollars) to $millions; null reads as 0. Calculation inputs
     *  are magnitudes, so the sign is dropped. */
    public static double dollarM(BigDecimal bd) {
        return bd != null ? bd.abs().doubleValue() / 1_000_000.0 : 0;
    }

    /** A stored percent/rate column to a fraction; -1 signals "not set" so callers can fall back.
     *  Rates and percents persist as fractions, but the {@code > 1} rescale is kept because the
     *  concentration limits still arrive on the percent scale (7.5 = 7.5%). */
    private static double parsePctDecimal(BigDecimal bd) {
        if (bd == null) return -1.0;
        double n = bd.doubleValue();
        return n > 1 ? n / 100.0 : n;
    }

    public BbResult compute(List<LpRecord> lps, double concLimitM) {
        double totalUcM = lps.stream()
            .mapToDouble(lpRecord -> dollarM(lpRecord.getUncalledCapital())).sum();
        List<ComputedLpRecord> computed = lps.stream()
            .map(lpRecord -> computeOne(lpRecord, concLimitM, totalUcM))
            .toList();

        List<ComputedLpRecord> included = computed.stream().filter(lpRecord -> lpRecord.included()).toList();

        double totalUBB = included.stream().mapToDouble(lpRecord -> lpRecord.ubbM()).sum();
        double totalABB = computed.stream().mapToDouble(lpRecord -> lpRecord.abbM()).sum();
        double totalUEC = included.stream().mapToDouble(lpRecord -> lpRecord.uecM()).sum();
        double totalUC  = included.stream().mapToDouble(lpRecord -> lpRecord.ucM()).sum();
        double totalConcExcess = computed.stream().mapToDouble(lpRecord -> lpRecord.concExcessM()).sum();
        int reclassCount = (int) computed.stream().filter(lpRecord -> lpRecord.reclassified()).count();

        double ear      = totalUEC > 0 ? totalUBB / totalUEC : 0;
        double agentEar = totalUEC > 0 ? totalABB / totalUEC : 0;

        // Pass 2: %-of-portfolio shares need the totals from pass 1.
        computed = computed.stream()
            .map(lpRecord -> lpRecord.withShares(
                totalABB > 0 ? lpRecord.abbM() / totalABB : 0,
                totalUBB > 0 ? lpRecord.ubbM() / totalUBB : 0))
            .toList();

        BbSummary summary = new BbSummary(
            totalUBB, totalABB, totalUBB - totalABB,
            ear, agentEar, ear - agentEar,
            totalUEC, totalUC, totalConcExcess,
            included.size(), computed.size() - included.size(), reclassCount
        );

        return new BbResult(computed, summary, detectBreaches(computed, totalUBB));
    }

    private ComputedLpRecord computeOne(LpRecord lpRecord, double facilityConc, double totalUcM) {
        Optional<BbCriteriaResolver.Criteria> criteria = resolveCriteria(lpRecord);
        double busaRate    = advanceRateFraction(lpRecord, criteria);
        boolean excluded   = !lpRecord.isIncluded() || "Excluded".equals(lpRecord.getUbsLpCategory());
        double ucM         = dollarM(lpRecord.getUncalledCapital());
        double abbM        = hasStoredAbb(lpRecord)
            ? dollarM(lpRecord.getAgentBorrowingBase())
            : deriveAgentBbM(lpRecord, ucM, totalUcM, excluded);
        double concLimitM  = perLpConc(lpRecord, facilityConc, totalUcM, criteria);
        double uecM        = excluded ? 0 : Math.min(ucM, concLimitM);
        double concExcessM = Math.max(0, ucM - uecM);
        double ubbM        = uecM * busaRate;
        double deltaM      = ubbM - abbM;
        double agentExcessM = excluded ? 0 : Math.max(0, ucM - agentEligibleM(lpRecord, ucM, totalUcM));
        return ComputedLpRecord.from(lpRecord, busaRate, ucM, uecM, ubbM, abbM, deltaM, concExcessM, agentExcessM);
    }

    /** True when the record carries an Agent BB value written by a row-bearing run — including an
     *  explicit "$0", which must not be re-derived. */
    private static boolean hasStoredAbb(LpRecord lpRecord) {
        return lpRecord.getAgentBorrowingBase() != null;
    }

    /**
     * Agent BB for records whose {@code abb} column was never populated (committed via the wizard
     * but only ever run through the row-less re-run path): eligible uncalled capital — capped by
     * the agent concentration limit as a percent of total uncalled — times the agent advance rate.
     * Mirrors the frontend {@code calcRow} (RunShadowBB) so both engines agree; without this
     * fallback such facilities report Agent BB = $0 despite populated agent rates.
     */
    private static double deriveAgentBbM(LpRecord lpRecord, double ucM, double totalUcM, boolean excluded) {
        if (excluded) return 0;
        double agentRate = parsePctDecimal(lpRecord.getAgentAdvanceRate());
        if (agentRate <= 0) return 0;
        return agentEligibleM(lpRecord, ucM, totalUcM) * agentRate;
    }

    /** Uncalled capital eligible under the agent's concentration limit (a percent of total
     *  uncalled); uncapped when no limit is stored. Shared by the Agent BB derivation and the
     *  agent excess-concentration figure so the two can never drift. */
    private static double agentEligibleM(LpRecord lpRecord, double ucM, double totalUcM) {
        double agentConcPct = parsePctDecimal(lpRecord.getAgentConcentrationLimit());
        return agentConcPct > 0 ? Math.min(ucM, agentConcPct * totalUcM) : ucM;
    }

    /** Per-LP concentration limit in $M. Chain: explicit per-LP limit stored in ubsConc as a
     *  percent of total uncalled capital ("7.5%"), with legacy dollar strings still parsed for old
     *  rows, then the {@code bb_criteria_matrix} default (rating-band aware for Rated Investors),
     *  then the facility-level dollar limit. No legacy classification-default fallback. */
    private static double perLpConc(LpRecord lpRecord, double facilityConc, double totalUcM,
                                    Optional<BbCriteriaResolver.Criteria> criteria) {
        String cls = lpRecord.getUbsLpCategory();
        // Evaluate the Excluded bucket first: an excluded LP is a hard 0 concentration
        // limit, ahead of any explicit per-LP override or class default, so a
        // stale/misconfigured default can never leak into the borrowing base.
        if (cls != null && "Excluded".equals(normalizeDashes(cls))) return 0;
        BigDecimal ubsConcLimitBd = lpRecord.getUbsConcentrationLimit();
        if (ubsConcLimitBd != null) {
            // The limit is either a percentage of total uncalled or an absolute dollar cap; both
            // share the one NUMERIC column and are told apart by magnitude (see ABSOLUTE_DOLLAR_MIN).
            if (ubsConcLimitBd.doubleValue() >= ABSOLUTE_DOLLAR_MIN) {
                double capM = dollarM(ubsConcLimitBd);
                if (capM > 0) return capM;
            } else {
                double pct = parsePctDecimal(ubsConcLimitBd);
                if (pct >= 0 && totalUcM > 0) return pct * totalUcM;
            }
        }
        // Matrix default (rating-band aware for Rated Investors), as a percent of total uncalled.
        if (criteria.isPresent() && criteria.get().concLimitPct() > 0 && totalUcM > 0) {
            return criteria.get().concLimitPct() / 100.0 * totalUcM;
        }
        return facilityConc;
    }

    /** The legacy tier label "Unrated 1–2bn" circulates with both an en dash (engine,
     *  reports) and a hyphen (seeded config keys) — normalize before map lookups. */
    private static String normalizeDashes(String s) {
        return s.replace('–', '-').replace('—', '-').replace('‐', '-');
    }

    /** The top-10 warning fires within this many percentage points below the breach limit
     *  (config default 60% → warning band 50–60%, matching the documented process flow). */
    private static final double TOP10_WARNING_BAND = 0.10;

    /** Breach thresholds as fractions, sourced from the {@code conc_limits} config rows
     *  (Config screen → Concentration Limits). Rows are keyed by their fixed labels; a
     *  missing row or config key falls back to the seeded default so detection never
     *  silently switches off. Rows without an engine rule (e.g. "Pension fund max") are
     *  ignored here. */
    private record ConcLimits(double singleLp, double top10, double unrated, double nonUs) {}

    private ConcLimits loadConcLimits() {
        double singleLp = 0.15, top10 = 0.60, unrated = 0.50, nonUs = 0.30;
        JsonNode rows = configService.get("conc_limits").orElse(null);
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                double pct = row.path("value").asDouble(-1);
                if (pct < 0) continue;
                switch (row.path("label").asString()) {
                    case "Single LP max"           -> singleLp = pct / 100.0;
                    case "Top-10 LP max"           -> top10    = pct / 100.0;
                    case "Unrated max (aggregate)" -> unrated  = pct / 100.0;
                    case "Non-US LP max"           -> nonUs    = pct / 100.0;
                    default -> { }
                }
            }
        }
        return new ConcLimits(singleLp, top10, unrated, nonUs);
    }

    private List<BbBreach> detectBreaches(List<ComputedLpRecord> lps, double totalUBB) {
        List<BbBreach> breaches = new ArrayList<>();
        if (totalUBB <= 0) return breaches;

        ConcLimits limits = loadConcLimits();
        List<ComputedLpRecord> included = lps.stream().filter(lpRecord -> lpRecord.included()).toList();

        // Single LP over configured limit
        for (ComputedLpRecord lpRecord : included) {
            double pct = lpRecord.ubbM() / totalUBB;
            if (pct > limits.singleLp()) {
                breaches.add(new BbBreach("single-LP", "breach",
                    lpRecord.investorName() + " exceeds " + pctLabel(limits.singleLp()) + " single-LP concentration",
                    pct, limits.singleLp()));
            }
        }

        // Top-10 over configured limit (warning inside the band below it)
        double top10UBB = included.stream()
            .sorted(Comparator.comparingDouble((ComputedLpRecord lpRecord) -> lpRecord.ubbM()).reversed())
            .limit(10)
            .mapToDouble(lpRecord -> lpRecord.ubbM()).sum();
        double top10Pct  = top10UBB / totalUBB;
        double top10Warn = Math.max(0, limits.top10() - TOP10_WARNING_BAND);
        if (top10Pct > limits.top10()) {
            breaches.add(new BbBreach("top10", "breach",
                "Top-10 LPs exceed " + pctLabel(limits.top10()) + " of UBS BB", top10Pct, limits.top10()));
        } else if (top10Pct > top10Warn) {
            breaches.add(new BbBreach("top10", "warning",
                "Top-10 LPs between " + pctLabel(top10Warn) + "–" + pctLabel(limits.top10()) + " of UBS BB",
                top10Pct, limits.top10()));
        }

        // Unrated aggregate over configured limit
        double unratedUBB = included.stream()
            .filter(lpRecord -> !lpRecord.highQuality())
            .mapToDouble(lpRecord -> lpRecord.ubbM()).sum();
        if (unratedUBB / totalUBB > limits.unrated()) {
            breaches.add(new BbBreach("unrated", "breach",
                "UnRated LP aggregate exceeds " + pctLabel(limits.unrated()) + " of UBS BB",
                unratedUBB / totalUBB, limits.unrated()));
        }

        // Non-US aggregate over configured limit
        double nonUsUBB = included.stream()
            .filter(lpRecord -> !lpRecord.highQuality())
            .mapToDouble(lpRecord -> lpRecord.ubbM()).sum();
        if (nonUsUBB / totalUBB > limits.nonUs()) {
            breaches.add(new BbBreach("non-us", "breach",
                "Non-US LP aggregate exceeds " + pctLabel(limits.nonUs()) + " of UBS BB",
                nonUsUBB / totalUBB, limits.nonUs()));
        }

        return breaches;
    }

    /** "0.15" → "15%", "0.075" → "7.5%". */
    private static String pctLabel(double fraction) {
        double pct = fraction * 100;
        return (pct == Math.rint(pct) ? String.valueOf((long) pct) : String.valueOf(pct)) + "%";
    }

    /**
     * Parses formatted money strings like "$25.0M", "$1.4T", "$620M" into $millions.
     * Suffix-less values mirror the TS reference (parseM in bbCalculationService.ts): a
     * dollar sign or a magnitude ≥ 100,000 marks an absolute-dollar amount ("$500000" →
     * 0.5), anything else is already in $millions. Without this branch the two engines
     * diverge by six orders of magnitude on sub-$1M amounts.
     */
    public static double parseMoney(String s) {
        s = normalizeSignedMoneyNotation(s);
        if (s == null || s.isBlank() || "N/A".equals(s) || "—".equals(s)) return 0;
        String clean = s.replaceAll("[$,]", "").replace("–", "-");
        double mult = 1;
        boolean suffixed = true;
        if (clean.toUpperCase().endsWith("T")) { mult = 1_000_000; clean = clean.substring(0, clean.length() - 1); }
        else if (clean.toUpperCase().endsWith("B")) { mult = 1_000;   clean = clean.substring(0, clean.length() - 1); }
        else if (clean.toUpperCase().endsWith("M")) { mult = 1;       clean = clean.substring(0, clean.length() - 1); }
        else if (clean.toUpperCase().endsWith("K")) { mult = 0.001;   clean = clean.substring(0, clean.length() - 1); }
        else { suffixed = false; }
        try {
            double value = Double.parseDouble(clean) * mult;
            if (!suffixed && (s.contains("$") || Math.abs(value) >= ABSOLUTE_DOLLAR_MIN)) {
                return value / 1_000_000;
            }
            return value;
        }
        catch (NumberFormatException e) { return 0; }
    }

    private static String normalizeSignedMoneyNotation(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        boolean accountingNegative = value.startsWith("(") && value.endsWith(")");
        if (accountingNegative) value = value.substring(1, value.length() - 1).trim();
        boolean trailingMinus = value.endsWith("-");
        if (trailingMinus) value = value.substring(0, value.length() - 1).trim();
        value = value.replaceAll("\\s+", "");
        return accountingNegative || trailingMinus ? "-" + value.replaceFirst("^-", "") : value;
    }
}
