package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.BbBreach;
import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.dto.BbSummary;
import com.ubs.pesubapi.dto.ComputedLp;
import com.ubs.pesubapi.entity.Lp;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Port of pe-sub-common/src/engine/calculator.ts.
 * Produces identical numbers to the TypeScript version.
 */
@Service
public class BbCalculationService {

    private final ConfigService configService;

    public BbCalculationService(ConfigService configService) {
        this.configService = configService;
    }

    /** Returns the BUSA advance rate for a given LP classification. */
    public double getRateForCls(String cls) {
        if (cls == null || cls.isBlank()) return 0.0;
        JsonNode cfg = configService.get("classification_config").orElse(null);
        if (cfg == null) return 0.0;

        double legacy = parsePct(cfg.path("BUSA_RATE_MAP").path(cls).asText(""));
        if (legacy >= 0.0) return legacy;

        double ubs = parsePct(cfg.path("UBS_CLS_DEFAULT_RATE").path(cls).asText(""));
        return ubs >= 0.0 ? ubs : 0.0;
    }

    // The UBS Advance Rate is an independent manual-input column on the Shadow BB: the same class
    // may appear at 0.90 / 0.75 / 0.65 / 0.00 across the population, so the stored per-LP rate
    // takes precedence over the classification default. Mirrors the frontend advanceRateFraction
    // (bbCalculationService.ts). "90%"/"90" → 0.90, "0.90" → 0.90; blank → classification default.
    public double advanceRateFraction(Lp lp) {
        String raw = lp.getUbsRate();
        if (raw != null && !raw.isBlank()) {
            try {
                double n = Double.parseDouble(raw.replace("%", "").trim());
                return n > 1 ? n / 100.0 : n;
            } catch (NumberFormatException ignored) { /* fall through to class default */ }
        }
        return getRateForCls(lp.getCls());
    }

    private static double parsePct(String raw) {
        if (raw == null || raw.isBlank()) return -1.0;
        try {
            double n = Double.parseDouble(raw.replace("%", "").trim());
            return n > 1 ? n / 100.0 : n;
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    public BbResult compute(List<Lp> lps, double concLimitM) {
        List<ComputedLp> computed = lps.stream()
            .map(lp -> computeOne(lp, concLimitM))
            .toList();

        List<ComputedLp> included = computed.stream().filter(lp -> lp.inc()).toList();

        double totalUBB = included.stream().mapToDouble(lp -> lp.ubbM()).sum();
        double totalABB = computed.stream().mapToDouble(lp -> lp.abbM()).sum();
        double totalUEC = included.stream().mapToDouble(lp -> lp.uecM()).sum();

        double ear      = totalUEC > 0 ? totalUBB / totalUEC : 0;
        double agentEar = totalUEC > 0 ? totalABB / totalUEC : 0;

        BbSummary summary = new BbSummary(
            totalUBB, totalABB, totalUBB - totalABB,
            ear, agentEar, ear - agentEar,
            included.size(), computed.size() - included.size()
        );

        return new BbResult(computed, summary, detectBreaches(computed, totalUBB));
    }

    private ComputedLp computeOne(Lp lp, double facilityConc) {
        double busaRate    = advanceRateFraction(lp);
        boolean excluded   = !lp.isInc() || "Excluded".equals(lp.getCls());
        double ucM         = moneyM(lp.getUcNum(),  lp.getUc());
        double abbM        = moneyM(lp.getAbbNum(), lp.getAbb());
        // Use per-LP dollar limit stored in ubsConc (e.g. "$25.0M") when present;
        // fall back to the facility-level concLimitM for LPs without a stored override.
        double concLimitM  = perLpConc(lp.getUbsConc(), facilityConc);
        double uecM        = excluded ? 0 : Math.min(ucM, concLimitM);
        double concExcessM = Math.max(0, ucM - uecM);
        double ubbM        = uecM * busaRate;
        double deltaM      = ubbM - abbM;
        return ComputedLp.from(lp, busaRate, uecM, ubbM, abbM, deltaM, concExcessM);
    }

    private static double perLpConc(String ubsConc, double facilityConc) {
        if (ubsConc == null || ubsConc.isBlank() || ubsConc.contains("%")) return facilityConc;
        double v = parseMoney(ubsConc);
        return v > 0 ? v : facilityConc;
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
                switch (row.path("label").asText()) {
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

    private List<BbBreach> detectBreaches(List<ComputedLp> lps, double totalUBB) {
        List<BbBreach> breaches = new ArrayList<>();
        if (totalUBB <= 0) return breaches;

        ConcLimits limits = loadConcLimits();
        List<ComputedLp> included = lps.stream().filter(lp -> lp.inc()).toList();

        // Single LP over configured limit
        for (ComputedLp lp : included) {
            double pct = lp.ubbM() / totalUBB;
            if (pct > limits.singleLp()) {
                breaches.add(new BbBreach("single-lp", "breach",
                    lp.name() + " exceeds " + pctLabel(limits.singleLp()) + " single-LP concentration",
                    pct, limits.singleLp()));
            }
        }

        // Top-10 over configured limit (warning inside the band below it)
        double top10UBB = included.stream()
            .sorted(Comparator.comparingDouble((ComputedLp lp) -> lp.ubbM()).reversed())
            .limit(10)
            .mapToDouble(lp -> lp.ubbM()).sum();
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
            .filter(lp -> !lp.highQuality())
            .mapToDouble(lp -> lp.ubbM()).sum();
        if (unratedUBB / totalUBB > limits.unrated()) {
            breaches.add(new BbBreach("unrated", "breach",
                "Unrated LP aggregate exceeds " + pctLabel(limits.unrated()) + " of UBS BB",
                unratedUBB / totalUBB, limits.unrated()));
        }

        // Non-US aggregate over configured limit
        double nonUsUBB = included.stream()
            .filter(lp -> !lp.hq())
            .mapToDouble(lp -> lp.ubbM()).sum();
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
     * Money in $millions, numeric-first (C2): the precise numeric column (absolute dollars) when
     * present, otherwise the legacy formatted display string. Lets the engine read exact values
     * for rows written after the numeric migration while staying correct for pre-migration rows.
     */
    public static double moneyM(BigDecimal numericDollars, String display) {
        if (numericDollars != null) return numericDollars.doubleValue() / 1_000_000.0;
        return parseMoney(display);
    }

    /**
     * Parses formatted money strings like "$25.0M", "$1.4T", "$620M" into $millions.
     * Suffix-less values mirror the TS reference (parseM in bbCalculationService.ts): a
     * dollar sign or a magnitude ≥ 100,000 marks an absolute-dollar amount ("$500000" →
     * 0.5), anything else is already in $millions. Without this branch the two engines
     * diverge by six orders of magnitude on sub-$1M amounts.
     */
    public static double parseMoney(String s) {
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
            if (!suffixed && (s.contains("$") || Math.abs(value) >= 100_000)) {
                return value / 1_000_000;
            }
            return value;
        }
        catch (NumberFormatException e) { return 0; }
    }
}
