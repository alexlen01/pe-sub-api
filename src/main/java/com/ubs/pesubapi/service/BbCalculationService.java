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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Returns the BUSA advance rate for a given LP Classification. */
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
    public double advanceRateFraction(LpRecord lpRecord) {
        String raw = lpRecord.getUbsRate();
        if (raw != null && !raw.isBlank()) {
            try {
                double n = Double.parseDouble(raw.replace("%", "").trim());
                return n > 1 ? n / 100.0 : n;
            } catch (NumberFormatException ignored) { /* fall through to class default */ }
        }
        return getRateForCls(lpRecord.getCls());
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

    public BbResult compute(List<LpRecord> lps, double concLimitM) {
        double totalUcM = lps.stream()
            .mapToDouble(lpRecord -> moneyM(lpRecord.getUcNum(), lpRecord.getUc())).sum();
        Map<String, Double> clsConcDefaults = loadClsConcDefaults();
        List<ComputedLpRecord> computed = lps.stream()
            .map(lpRecord -> computeOne(lpRecord, concLimitM, totalUcM, clsConcDefaults))
            .toList();

        List<ComputedLpRecord> included = computed.stream().filter(lpRecord -> lpRecord.inc()).toList();

        double totalUBB = included.stream().mapToDouble(lpRecord -> lpRecord.ubbM()).sum();
        double totalABB = computed.stream().mapToDouble(lpRecord -> lpRecord.abbM()).sum();
        double totalUEC = included.stream().mapToDouble(lpRecord -> lpRecord.uecM()).sum();

        double ear      = totalUEC > 0 ? totalUBB / totalUEC : 0;
        double agentEar = totalUEC > 0 ? totalABB / totalUEC : 0;

        BbSummary summary = new BbSummary(
            totalUBB, totalABB, totalUBB - totalABB,
            ear, agentEar, ear - agentEar,
            included.size(), computed.size() - included.size()
        );

        return new BbResult(computed, summary, detectBreaches(computed, totalUBB));
    }

    private ComputedLpRecord computeOne(LpRecord lpRecord, double facilityConc, double totalUcM,
                                        Map<String, Double> clsConcDefaults) {
        double busaRate    = advanceRateFraction(lpRecord);
        boolean excluded   = !lpRecord.isInc() || "Excluded".equals(lpRecord.getCls());
        double ucM         = moneyM(lpRecord.getUcNum(),  lpRecord.getUc());
        double abbM        = moneyM(lpRecord.getAbbNum(), lpRecord.getAbb());
        double concLimitM  = perLpConc(lpRecord, facilityConc, totalUcM, clsConcDefaults);
        double uecM        = excluded ? 0 : Math.min(ucM, concLimitM);
        double concExcessM = Math.max(0, ucM - uecM);
        double ubbM        = uecM * busaRate;
        double deltaM      = ubbM - abbM;
        return ComputedLpRecord.from(lpRecord, busaRate, uecM, ubbM, abbM, deltaM, concExcessM);
    }

    /** Per-LP concentration limit in $M. Fallback chain (mirrors the Run Shadow BB
     *  screen): explicit per-LP limit stored in ubsConc as a percent of total
     *  uncalled capital ("7.5%"), with legacy dollar strings still parsed for old
     *  rows, then the classification default from {@code cls_conc_limit_defaults},
     *  then the facility-level dollar limit. */
    private static double perLpConc(LpRecord lpRecord, double facilityConc, double totalUcM,
                                    Map<String, Double> clsConcDefaults) {
        String cls = lpRecord.getCls();
        // Evaluate the Excluded bucket first: an excluded LP is a hard 0 concentration
        // limit, ahead of any explicit per-LP override or class residual default, so a
        // stale/misconfigured Excluded default can never leak into the borrowing base.
        if (cls != null && "Excluded".equals(normalizeDashes(cls))) return 0;
        String ubsConc = lpRecord.getUbsConc();
        if (ubsConc != null && !ubsConc.isBlank()) {
            if (ubsConc.contains("%")) {
                double pct = parsePct(ubsConc);
                if (pct >= 0 && totalUcM > 0) return pct * totalUcM;
            } else {
                double v = parseMoney(ubsConc);
                if (v > 0) return v;
            }
        }
        Double defaultPct = cls == null ? null : clsConcDefaults.get(normalizeDashes(cls));
        if (defaultPct != null && defaultPct > 0 && totalUcM > 0) return defaultPct / 100.0 * totalUcM;
        return facilityConc;
    }

    /** Class-default per-LP concentration limits (percent of total uncalled capital)
     *  from the {@code cls_conc_limit_defaults} config key, keyed by dash-normalized
     *  classification label. Empty when the key is not seeded — the facility-level
     *  fallback then applies, so detection of a missing config never breaks a run. */
    private Map<String, Double> loadClsConcDefaults() {
        Map<String, Double> out = new HashMap<>();
        JsonNode node = configService.get("cls_conc_limit_defaults").orElse(null);
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                double pct = e.getValue().asDouble(-1);
                if (pct >= 0) out.put(normalizeDashes(e.getKey()), pct);
            }
        }
        return out;
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

    private List<BbBreach> detectBreaches(List<ComputedLpRecord> lps, double totalUBB) {
        List<BbBreach> breaches = new ArrayList<>();
        if (totalUBB <= 0) return breaches;

        ConcLimits limits = loadConcLimits();
        List<ComputedLpRecord> included = lps.stream().filter(lpRecord -> lpRecord.inc()).toList();

        // Single LP over configured limit
        for (ComputedLpRecord lpRecord : included) {
            double pct = lpRecord.ubbM() / totalUBB;
            if (pct > limits.singleLp()) {
                breaches.add(new BbBreach("single-LP", "breach",
                    lpRecord.name() + " exceeds " + pctLabel(limits.singleLp()) + " single-LP concentration",
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
            .filter(lpRecord -> !lpRecord.hq())
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
