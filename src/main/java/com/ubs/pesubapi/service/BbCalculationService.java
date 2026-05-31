package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.BbBreach;
import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.dto.BbSummary;
import com.ubs.pesubapi.dto.ComputedLp;
import com.ubs.pesubapi.entity.Lp;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Port of pe-sub-common/src/engine/calculator.ts.
 * Produces identical numbers to the TypeScript version.
 */
@Service
public class BbCalculationService {

    private static final Map<String, Double> BUSA_RATES = Map.of(
        "Rated",          0.90,
        "Unrated >2bn",   0.75,
        "Unrated 1–2bn", 0.65,   // em dash: "Unrated 1–2bn"
        "Eligible",       0.50,
        "Excluded",       0.00
    );

    public BbResult compute(List<Lp> lps, double concLimitM) {
        List<ComputedLp> computed = lps.stream()
            .map(lp -> computeOne(lp, concLimitM))
            .toList();

        List<ComputedLp> included = computed.stream().filter(ComputedLp::inc).toList();

        double totalUBB = included.stream().mapToDouble(ComputedLp::ubbM).sum();
        double totalABB = computed.stream().mapToDouble(ComputedLp::abbM).sum();
        double totalUEC = included.stream().mapToDouble(ComputedLp::uecM).sum();

        double ear      = totalUEC > 0 ? totalUBB / totalUEC : 0;
        double agentEar = totalUEC > 0 ? totalABB / totalUEC : 0;

        BbSummary summary = new BbSummary(
            totalUBB, totalABB, totalUBB - totalABB,
            ear, agentEar, ear - agentEar,
            included.size(), computed.size() - included.size()
        );

        return new BbResult(computed, summary, detectBreaches(computed, totalUBB));
    }

    private ComputedLp computeOne(Lp lp, double concLimitM) {
        double busaRate     = BUSA_RATES.getOrDefault(lp.getCls(), 0.0);
        boolean excluded    = !lp.isInc() || "Excluded".equals(lp.getCls());
        double ucM          = parseMoney(lp.getUc());
        double abbM         = parseMoney(lp.getAbb());
        double uecM         = excluded ? 0 : Math.min(ucM, concLimitM);
        double concExcessM  = Math.max(0, ucM - uecM);
        double ubbM         = uecM * busaRate;
        double deltaM       = ubbM - abbM;
        return ComputedLp.from(lp, busaRate, uecM, ubbM, abbM, deltaM, concExcessM);
    }

    private List<BbBreach> detectBreaches(List<ComputedLp> lps, double totalUBB) {
        List<BbBreach> breaches = new ArrayList<>();
        if (totalUBB <= 0) return breaches;

        List<ComputedLp> included = lps.stream().filter(ComputedLp::inc).toList();

        // Single LP > 15%
        for (ComputedLp lp : included) {
            double pct = lp.ubbM() / totalUBB;
            if (pct > 0.15) {
                breaches.add(new BbBreach("single-lp", "breach",
                    lp.name() + " exceeds 15% single-LP concentration", pct, 0.15));
            }
        }

        // Top-10 > 60% (warning at 50%)
        double top10UBB = included.stream()
            .sorted(Comparator.comparingDouble(ComputedLp::ubbM).reversed())
            .limit(10)
            .mapToDouble(ComputedLp::ubbM).sum();
        double top10Pct = top10UBB / totalUBB;
        if (top10Pct > 0.60) {
            breaches.add(new BbBreach("top10", "breach",
                "Top-10 LPs exceed 60% of UBS BB", top10Pct, 0.60));
        } else if (top10Pct > 0.50) {
            breaches.add(new BbBreach("top10", "warning",
                "Top-10 LPs between 50–60% of UBS BB", top10Pct, 0.60));
        }

        // Unrated aggregate > 50%
        double unratedUBB = included.stream()
            .filter(lp -> !lp.highQuality())
            .mapToDouble(ComputedLp::ubbM).sum();
        if (unratedUBB / totalUBB > 0.50) {
            breaches.add(new BbBreach("unrated", "breach",
                "Unrated LP aggregate exceeds 50% of UBS BB", unratedUBB / totalUBB, 0.50));
        }

        // Non-US aggregate > 30%
        double nonUsUBB = included.stream()
            .filter(lp -> !lp.hq())
            .mapToDouble(ComputedLp::ubbM).sum();
        if (nonUsUBB / totalUBB > 0.30) {
            breaches.add(new BbBreach("non-us", "breach",
                "Non-US LP aggregate exceeds 30% of UBS BB", nonUsUBB / totalUBB, 0.30));
        }

        return breaches;
    }

    /** Parses formatted money strings like "$25.0M", "$1.4T", "$620M". */
    static double parseMoney(String s) {
        if (s == null || s.isBlank() || "N/A".equals(s) || "—".equals(s)) return 0;
        String clean = s.replaceAll("[$,]", "").replace("–", "-");
        double mult = 1;
        if (clean.toUpperCase().endsWith("T")) { mult = 1_000_000; clean = clean.substring(0, clean.length() - 1); }
        else if (clean.toUpperCase().endsWith("B")) { mult = 1_000;   clean = clean.substring(0, clean.length() - 1); }
        else if (clean.toUpperCase().endsWith("M")) { mult = 1;       clean = clean.substring(0, clean.length() - 1); }
        else if (clean.toUpperCase().endsWith("K")) { mult = 0.001;   clean = clean.substring(0, clean.length() - 1); }
        try { return Double.parseDouble(clean) * mult; }
        catch (NumberFormatException e) { return 0; }
    }
}
