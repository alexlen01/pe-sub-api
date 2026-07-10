package com.ubs.pesubapi.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden-table verification of the Borrowing Base Criteria resolver against
 * Concentration_Limits.xlsx tabs 2-3 — every classification × funded state × rated band, plus the
 * tri-party middle-rating waterfall. Uses a stubbed ConfigService holding the seeded matrix so the
 * table is exercised without a database.
 */
class BbCriteriaResolverTest {

    // Mirrors the bb_criteria_matrix seeded in V1_3__config.sql. TEST ONLY
    private static final String MATRIX_JSON = """
        {
          "fundedThresholdPct": 40,
          "ratingBands": {
            "AAA": { "sp": ["AAA"],               "moodys": ["Aaa"],               "fitch": ["AAA"] },
            "AA":  { "sp": ["AA+","AA","AA-"],     "moodys": ["Aa1","Aa2","Aa3"],   "fitch": ["AA+","AA","AA-"] },
            "A":   { "sp": ["A+","A","A-"],        "moodys": ["A1","A2","A3"],      "fitch": ["A+","A","A-"] },
            "BBB": { "sp": ["BBB+","BBB","BBB-"],  "moodys": ["Baa1","Baa2","Baa3"], "fitch": ["BBB+","BBB","BBB-"] }
          },
          "ratingBandSelection": "middle",
          "ratingBandTieBreak": { "three": "middle", "two": "lower", "one": "asIs" },
          "subInvestmentGradeBand": "BBB",
          "rated": [
            { "band": "AAA", "concLimitPct": 25, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
            { "band": "AA",  "concLimitPct": 20, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
            { "band": "A",   "concLimitPct": 15, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
            { "band": "BBB", "concLimitPct": 10, "advanceRatePct": { "lt40": 65, "gte40": 90 } }
          ],
          "classes": [
            { "cls": "Corp Pension > $5Bn Assets", "concLimitPct": 25, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
            { "cls": "Corp Pension > $1Bn Assets", "concLimitPct": 20, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
            { "cls": "Unrated NAV > $1Bn",         "concLimitPct": 15, "advanceRatePct": { "lt40": 90, "gte40": 90 } },
            { "cls": "FoF & Other > $10Bn AUM",    "concLimitPct": 10, "advanceRatePct": { "lt40": 65, "gte40": 75 } },
            { "cls": "Other Institutional",        "concLimitPct": 5,  "advanceRatePct": { "lt40": 50, "gte40": 65 } },
            { "cls": "HNW Feeder (acceptable)",    "concLimitPct": 5,  "advanceRatePct": { "lt40": 50, "gte40": 65 } },
            { "cls": "HNW (acceptable)",           "concLimitPct": 1,  "advanceRatePct": { "lt40": 0,  "gte40": 50 } },
            { "cls": "Excluded",                   "concLimitPct": 0,  "advanceRatePct": { "lt40": 0,  "gte40": 0 } }
          ]
        }
        """;

    private static final double LT40 = 0.0;    // 0% funded  → below threshold
    private static final double GTE40 = 0.40;  // exactly 40% funded → at/above threshold (inclusive)

    private final BbCriteriaResolver resolver = build();

    private static BbCriteriaResolver build() {
        JsonNode matrix = new ObjectMapper().readTree(MATRIX_JSON);
        ConfigService cfg = mock(ConfigService.class);
        when(cfg.get("bb_criteria_matrix")).thenReturn(Optional.of(matrix));
        return new BbCriteriaResolver(cfg);
    }

    private BbCriteriaResolver.Criteria rated(String sp, String mdy, String fitch, double funded) {
        return resolver.resolve("Rated Investor", sp, mdy, fitch, funded).orElseThrow();
    }

    private BbCriteriaResolver.Criteria cls(String c, double funded) {
        return resolver.resolve(c, "", "", "", funded).orElseThrow();
    }

    // ── Rated bands (single agency) ───────────────────────────────────────────────

    @Test void ratedAAA() {
        assertThat(rated("AAA", "", "", LT40).concLimitPct()).isEqualTo(25);
        assertThat(rated("AAA", "", "", LT40).advanceRatePct()).isEqualTo(90);
        assertThat(rated("", "Aaa", "", LT40).concLimitPct()).isEqualTo(25);   // Moody's mapping
    }

    @Test void ratedAA() { assertThat(rated("AA-", "", "", LT40).concLimitPct()).isEqualTo(20); }

    @Test void ratedA() { assertThat(rated("A+", "", "", LT40).concLimitPct()).isEqualTo(15); }

    @Test void ratedBBB_fundedSplit() {
        assertThat(rated("BBB", "", "", LT40).concLimitPct()).isEqualTo(10);
        assertThat(rated("BBB", "", "", LT40).advanceRatePct()).isEqualTo(65);   // < 40% funded
        assertThat(rated("BBB", "", "", GTE40).advanceRatePct()).isEqualTo(90);  // ≥ 40% funded
    }

    @Test void ratedSubInvestmentGradeClampsToBBB() {
        BbCriteriaResolver.Criteria c = rated("BB+", "", "", LT40);
        assertThat(c.concLimitPct()).isEqualTo(10);
        assertThat(c.advanceRatePct()).isEqualTo(65);
    }

    // ── Tri-party middle-rating waterfall ─────────────────────────────────────────

    @Test void threeRatingsAllDiffer_takesMiddle() {
        // AAA / AA / A → middle band AA → 20%
        assertThat(rated("AAA", "Aa2", "A", LT40).concLimitPct()).isEqualTo(20);
    }

    @Test void threeRatingsTwoMatch_takesMatch() {
        // A / A / BBB → median band A → 15%
        assertThat(rated("A", "A2", "BBB", LT40).concLimitPct()).isEqualTo(15);
    }

    @Test void twoRatings_takesLower() {
        // AA / A → lower band A → 15%
        assertThat(rated("AA", "A1", "", LT40).concLimitPct()).isEqualTo(15);
    }

    @Test void twoRatingsMatch_takesThatBand() {
        assertThat(rated("AA", "", "AA", LT40).concLimitPct()).isEqualTo(20);
    }

    @Test void threeRatingsOneSubIg_clampsThenMedian() {
        // AAA / Ba1(→BBB) / BBB → bands AAA, BBB, BBB → median BBB → 10%
        assertThat(rated("AAA", "Ba1", "BBB", LT40).concLimitPct()).isEqualTo(10);
    }

    // ── Non-rated classes × funded split ──────────────────────────────────────────

    @Test void corpPension5Bn() {
        assertThat(cls("Corp Pension > $5Bn Assets", LT40).concLimitPct()).isEqualTo(25);
        assertThat(cls("Corp Pension > $5Bn Assets", LT40).advanceRatePct()).isEqualTo(90);
    }

    @Test void corpPension1Bn() {
        assertThat(cls("Corp Pension > $1Bn Assets", LT40).concLimitPct()).isEqualTo(20);
        assertThat(cls("Corp Pension > $1Bn Assets", LT40).advanceRatePct()).isEqualTo(90);
    }

    @Test void unratedNav() { assertThat(cls("Unrated NAV > $1Bn", LT40).advanceRatePct()).isEqualTo(90); }

    @Test void fofFundedSplit() {
        assertThat(cls("FoF & Other > $10Bn AUM", LT40).advanceRatePct()).isEqualTo(65);
        assertThat(cls("FoF & Other > $10Bn AUM", GTE40).advanceRatePct()).isEqualTo(75);
        assertThat(cls("FoF & Other > $10Bn AUM", LT40).concLimitPct()).isEqualTo(10);
    }

    @Test void otherInstitutionalFundedSplit() {
        assertThat(cls("Other Institutional", LT40).advanceRatePct()).isEqualTo(50);
        assertThat(cls("Other Institutional", GTE40).advanceRatePct()).isEqualTo(65);
        assertThat(cls("Other Institutional", LT40).concLimitPct()).isEqualTo(5);
    }

    @Test void hnwFeederFundedSplit() {
        assertThat(cls("HNW Feeder (acceptable)", LT40).advanceRatePct()).isEqualTo(50);
        assertThat(cls("HNW Feeder (acceptable)", GTE40).advanceRatePct()).isEqualTo(65);
        assertThat(cls("HNW Feeder (acceptable)", LT40).concLimitPct()).isEqualTo(5);
    }

    @Test void hnwFundedSplit() {
        assertThat(cls("HNW (acceptable)", LT40).advanceRatePct()).isEqualTo(0);    // 0% until 40% funded
        assertThat(cls("HNW (acceptable)", GTE40).advanceRatePct()).isEqualTo(50);
        assertThat(cls("HNW (acceptable)", LT40).concLimitPct()).isEqualTo(1);
    }

    @Test void excluded() {
        assertThat(cls("Excluded", LT40).advanceRatePct()).isEqualTo(0);
        assertThat(cls("Excluded", LT40).concLimitPct()).isEqualTo(0);
    }

    // ── Boundary & fallthrough ────────────────────────────────────────────────────

    @Test void fundedThresholdIsInclusive() {
        // Exactly 40% funded takes the ≥ column.
        assertThat(cls("FoF & Other > $10Bn AUM", 0.40).advanceRatePct()).isEqualTo(75);
        assertThat(cls("FoF & Other > $10Bn AUM", 0.3999).advanceRatePct()).isEqualTo(65);
    }

    @Test void unknownClassResolvesEmpty() {
        assertThat(resolver.resolve("Legacy Institutional", "", "", "", LT40)).isEmpty();
        assertThat(resolver.resolve("", "", "", "", LT40)).isEmpty();
    }
}
