package com.ubs.pesubapi.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the Borrowing Base Criteria (advance rate + concentration limit) for an LP from the
 * {@code bb_criteria_matrix} config (Concentration_Limits.xlsx tabs 2-3). See
 * pe-sub-docs/BB_CRITERIA_DESIGN.md.
 *
 * <p>Two dimensions the flat legacy maps cannot express:
 * <ul>
 *   <li><b>Funded split</b> — advance rate steps at {@code fundedThresholdPct} on the LP's own
 *       funded % (called ÷ commitment). Concentration limit is funded-independent.</li>
 *   <li><b>Rating band</b> — Rated Investors resolve a band (AAA/AA/A/BBB) via the tri-party
 *       "eligible rating" waterfall over S&amp;P/Moody's/Fitch: three ratings → middle (median),
 *       two → the lower (conservative), one → that rating. A present rating that matches no band
 *       (sub-BBB-) clamps to the {@code subInvestmentGradeBand} (BBB) floor.</li>
 * </ul>
 *
 * <p>Returns {@link Optional#empty()} when the matrix is unconfigured or the classification is not
 * represented in it, so callers fall back to the legacy flat maps.
 */
@Service
public class BbCriteriaResolver {

    /** Best-to-worst band order; index is the ordinal used by the median/lower waterfall. */
    private static final String[] BAND_ORDER = {"AAA", "AA", "A", "BBB"};

    private final ConfigService configService;

    public BbCriteriaResolver(ConfigService configService) {
        this.configService = configService;
    }

    /** Resolved criteria for an LP. Both values are percentages (90.0 = 90%, 25.0 = 25%). */
    public record Criteria(double advanceRatePct, double concLimitPct) {}

    /**
     * @param cls       UBS LP Classification
     * @param sp        S&amp;P rating (blank / "NR" = absent)
     * @param mdy       Moody's rating
     * @param fitch     Fitch rating
     * @param pctFunded the LP's funded fraction (called ÷ commitment), 0..1
     */
    public Optional<Criteria> resolve(String cls, String sp, String mdy, String fitch, double pctFunded) {
        JsonNode m = configService.get("bb_criteria_matrix").orElse(null);
        if (m == null || cls == null || cls.isBlank()) return Optional.empty();

        String norm = normalize(cls);
        double threshold = m.path("fundedThresholdPct").asDouble(40) / 100.0;
        String funded = pctFunded >= threshold ? "gte40" : "lt40";

        // Rated Investor: resolve the band, then read that band's row.
        if (norm.equals("Rated Investor") || norm.equals("Rated")) {
            String band = resolveBand(m, sp, mdy, fitch);
            for (JsonNode row : m.path("rated")) {
                if (band.equals(row.path("band").asString(""))) {
                    return Optional.of(rowCriteria(row, funded));
                }
            }
            return Optional.empty();
        }

        // Non-rated class: direct lookup by classification label (dash-insensitive).
        for (JsonNode row : m.path("classes")) {
            if (normalize(row.path("cls").asString("")).equals(norm)) {
                return Optional.of(rowCriteria(row, funded));
            }
        }
        return Optional.empty();
    }

    private static Criteria rowCriteria(JsonNode row, String funded) {
        return new Criteria(
            row.path("advanceRatePct").path(funded).asDouble(0),
            row.path("concLimitPct").asDouble(0));
    }

    /**
     * Tri-party middle-rating waterfall → band string. Empty rating set falls to the sub-IG floor
     * (an edge case: a Rated Investor carrying no agency rating).
     */
    private String resolveBand(JsonNode m, String sp, String mdy, String fitch) {
        JsonNode bands = m.path("ratingBands");
        String subIG = m.path("subInvestmentGradeBand").asString("BBB");

        List<Integer> ords = new ArrayList<>(3);
        addOrdinal(ords, bands, "sp", sp, subIG);
        addOrdinal(ords, bands, "moodys", mdy, subIG);
        addOrdinal(ords, bands, "fitch", fitch, subIG);

        if (ords.isEmpty()) return subIG;
        Collections.sort(ords);                              // ascending = best → worst
        int chosen = switch (ords.size()) {
            case 1  -> ords.get(0);
            case 2  -> ords.get(1);                          // lower rating = higher ordinal
            default -> ords.get((ords.size() - 1) / 2);      // median (3 → index 1)
        };
        return BAND_ORDER[Math.min(chosen, BAND_ORDER.length - 1)];
    }

    /** Adds the band ordinal for one agency rating; sub-IG (present but unmatched) clamps to BBB. */
    private void addOrdinal(List<Integer> ords, JsonNode bands, String agency, String value, String subIG) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NR") || "N/A".equals(value)) return;
        for (int i = 0; i < BAND_ORDER.length; i++) {
            for (JsonNode r : bands.path(BAND_ORDER[i]).path(agency)) {
                if (r.asString("").equals(value)) { ords.add(i); return; }
            }
        }
        ords.add(indexOfBand(subIG));                        // sub-investment-grade floor
    }

    private static int indexOfBand(String band) {
        for (int i = 0; i < BAND_ORDER.length; i++) if (BAND_ORDER[i].equals(band)) return i;
        return BAND_ORDER.length - 1;
    }

    private static String normalize(String s) {
        return s.replace('–', '-').replace('—', '-').replace('‐', '-').trim();
    }
}
