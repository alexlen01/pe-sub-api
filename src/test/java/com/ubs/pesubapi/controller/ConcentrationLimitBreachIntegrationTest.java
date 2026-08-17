package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.FacilityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Breaches must be flagged on every Shadow BB run using the thresholds stored in the
 * {@code conc_limits} config key — the rows edited on the Config screen's Concentration
 * Limits card — not hardcoded engine constants.
 */
class ConcentrationLimitBreachIntegrationTest extends IntegrationTestBase {

    /** Mirrors the V1_2 seed so each test leaves the shared config cache as it found it. TEST ONLY */
    private static final String SEED_CONC_LIMITS = """
        [
          {"label":"Single LP max",           "value":15, "basis":"Total UBS BB"},
          {"label":"Top-10 LP max",           "value":60, "basis":"Total UBS BB"},
          {"label":"Unrated max (aggregate)", "value":50, "basis":"Total UBS BB"},
          {"label":"Non-US LP max",           "value":30, "basis":"Total UBS BB"},
          {"label":"Pension fund max",        "value":30, "basis":"Total eligible uncalled"}
        ]
        """;

    @Autowired MockMvc            mvc;
    @Autowired FacilityRepository facilityRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Apex Growth Fund IV");    // TEST ONLY
        f.setAgentBank("Wells Fargo");
        facilityId = facilityRepo.save(f).getId();
    }

    @AfterEach
    void restoreSeedConcLimits() throws Exception {
        putConcLimits(SEED_CONC_LIMITS);
    }

    // ── Configured limits drive detection ────────────────────────────────────────

    @Test
    void run_flagsNoBreachesWhenConfiguredLimitsAreLoose() throws Exception {
        // Two equal Rated LPs → each 50% of UBS BB, top-10 = 100%. Limits set above every
        // exposure (top-10 at 120 so its 10pp warning band sits above 100% too).
        putConcLimits("""
            [
              {"label":"Single LP max",           "value":80,  "basis":"Total UBS BB"},
              {"label":"Top-10 LP max",           "value":120, "basis":"Total UBS BB"},
              {"label":"Unrated max (aggregate)", "value":100, "basis":"Total UBS BB"},
              {"label":"Non-US LP max",           "value":100, "basis":"Total UBS BB"}
            ]
            """);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoEqualLpPayload()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.breaches", empty()));
    }

    @Test
    void run_flagsSingleLpBreachAtConfiguredLimit() throws Exception {
        // Same portfolio, single-LP cap tightened to 40% → both 50% LPs breach at limit 0.40.
        putConcLimits("""
            [
              {"label":"Single LP max",           "value":40,  "basis":"Total UBS BB"},
              {"label":"Top-10 LP max",           "value":120, "basis":"Total UBS BB"},
              {"label":"Unrated max (aggregate)", "value":100, "basis":"Total UBS BB"},
              {"label":"Non-US LP max",           "value":100, "basis":"Total UBS BB"}
            ]
            """);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoEqualLpPayload()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.breaches", hasSize(2)))
            .andExpect(jsonPath("$.result.breaches[*].type", everyItem(is("single-LP"))))
            .andExpect(jsonPath("$.result.breaches[0].severity").value("breach"))
            .andExpect(jsonPath("$.result.breaches[0].limit").value(closeTo(0.40, 0.001)))
            .andExpect(jsonPath("$.result.breaches[0].value").value(closeTo(0.50, 0.01)))
            .andExpect(jsonPath("$.result.breaches[0].message", containsString("40%")));

        // The persisted snapshot feeds the Reports screen with the same configured limit.
        mvc.perform(get("/api/reports/concentration/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.breaches", hasSize(2)))
            .andExpect(jsonPath("$.breaches[0].limit").value(closeTo(0.40, 0.001)));
    }

    @Test
    void run_missingConfigRowsFallBackToSeedDefaults() throws Exception {
        // Only the top-10 row is present at 90% → warning band 80–90%. Twelve equal Rated
        // US LPs put the top-10 share at 10/12 ≈ 83.3% (warning) while every omitted rule
        // keeps its default: single-LP 15% (each LpRecord is 8.3%), unrated 50% and non-US 30%
        // (both aggregates are zero). Exactly one warning must come back.
        putConcLimits("""
            [{"label":"Top-10 LP max", "value":90, "basis":"Total UBS BB"}]
            """);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(equalRatedLpPayload(12)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.breaches", hasSize(1)))
            .andExpect(jsonPath("$.result.breaches[0].type").value("top10"))
            .andExpect(jsonPath("$.result.breaches[0].severity").value("warning"))
            .andExpect(jsonPath("$.result.breaches[0].limit").value(closeTo(0.90, 0.001)))
            .andExpect(jsonPath("$.result.breaches[0].value").value(closeTo(10.0 / 12.0, 0.01)));
    }

    @Test
    void run_usesSeededConfigDefaultsOutOfTheBox() throws Exception {
        // No config change: the Flyway-seeded conc_limits rows (15/60/50/30) apply, so two
        // 50% LPs trip the single-LP test at 0.15 and the top-10 test at 0.60.
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoEqualLpPayload()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.breaches[?(@.type=='single-LP')].limit",
                everyItem(closeTo(0.15, 0.001))))
            .andExpect(jsonPath("$.result.breaches[?(@.type=='top10')].limit",
                everyItem(closeTo(0.60, 0.001))))
            .andExpect(jsonPath("$.result.breaches[?(@.type=='top10')].severity",
                contains("breach")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private void putConcLimits(String json) throws Exception {
        mvc.perform(put("/api/config/eligibility")
                .param("section", "conc_limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk());
    }

    /** Two identical Rated US LPs — each ends up at 50% of UBS BB. TEST ONLY */
    private static String twoEqualLpPayload() {
        return equalRatedLpPayload(2);
    }

    /** {@code n} identical Rated US LPs ($20M uncalled, $25M cap → $18M UBB each). TEST ONLY */
    private static String equalRatedLpPayload(int n) {
        String lps = IntStream.rangeClosed(1, n)
            .mapToObj(i -> """
                {
                  "name": "Equal LP %02d",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": true, "cls": "Rated Investor",
                  "sp": "AAA", "mdy": "Aaa", "fitch": "",
                  "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$20.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": null, "ubsConc": "$25.0M",
                  "agentRate": 0.95, "abb": "$19.0M",
                  "inc": true, "rcl": false, "notes": null
                }""".formatted(i))
            .collect(Collectors.joining(",\n"));
        return "{ \"lps\": [\n" + lps + "\n] }";
    }
}

