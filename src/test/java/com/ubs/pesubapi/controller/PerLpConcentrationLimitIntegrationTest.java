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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The per-LP concentration limit resolves through the Stage-2 chain:
 * explicit per-LP limit (dollar or percent of total uncalled)
 *   → {@code bb_criteria_matrix} (rating-band aware for Rated Investors)
 *   → facility-level dollar limit.
 * There is no legacy classification-default fallback: a class the matrix does not carry, with no
 * explicit per-LP limit, falls straight to the facility limit ({@code cls_conc_limit_defaults} is
 * not consulted by the borrowing-base engine).
 */
class PerLpConcentrationLimitIntegrationTest extends IntegrationTestBase {

    /** Mirrors the V1_3 seed so each test leaves the shared config cache as it found it. TEST ONLY */
    private static final String SEED_CLS_CONC_DEFAULTS = """
        {
          "Rated Investor": 20.0,
          "Corp Pension > $5Bn Assets": 12.5,
          "Corp Pension > $1Bn Assets": 20.0,
          "Unrated NAV > $1Bn": 15.0,
          "FoF & Other > $10Bn AUM": 10.0,
          "Other Institutional": 7.5,
          "HNW Feeder (acceptable)": 5.0,
          "HNW (acceptable)": 1.0,
          "Excluded": 0.0
        }
        """;

    @Autowired MockMvc            mvc;
    @Autowired FacilityRepository facilityRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Meridian Capital Fund II");   // TEST ONLY
        f.setAgentBank("State Street");
        facilityId = facilityRepo.save(f).getId();
    }

    @AfterEach
    void restoreSeedDefaults() throws Exception {
        putClsConcDefaults(SEED_CLS_CONC_DEFAULTS);
    }

    @Test
    void run_matrixBandLimitCapsRatedInvestor() throws Exception {
        // Two AAA Rated LPs at $20M uncalled each (total $40M), no per-LP limit. The matrix caps a
        // Rated AAA at 25% of total uncalled → $10M each; 90% advance → ubb $9M. The tightened
        // cls_conc_limit_defaults entry has no effect — the engine no longer consults it.
        putClsConcDefaults("""
            {"Rated Investor": 5}
            """);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoRatedPayload(null, null)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[*].uecM", everyItem(closeTo(10.0, 0.001))))
            .andExpect(jsonPath("$.result.lps[*].ubbM", everyItem(closeTo(9.0, 0.001))));
    }

    @Test
    void run_perLpPercentLimitBeatsMatrix() throws Exception {
        // First LP carries an explicit 10% limit → $4M of the $40M total uncalled; the second falls
        // to the matrix Rated AAA default of 25% → $10M.
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoRatedPayload("10%", null)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[?(@.investorName=='Chain LP 01')].uecM", contains(closeTo(4.0, 0.001))))
            .andExpect(jsonPath("$.result.lps[?(@.investorName=='Chain LP 02')].uecM", contains(closeTo(10.0, 0.001))));
    }

    @Test
    void run_perLpDollarLimitBeatsMatrix() throws Exception {
        // An explicit, binding $4M dollar limit wins over the matrix's 25% ($10M of the $40M total):
        // uecM = min($20M, $4M) = $4M → ubbM = $3.6M at the 90% rate.
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoRatedPayload("$4.0M", "$4.0M")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[*].uecM", everyItem(closeTo(4.0, 0.001))))
            .andExpect(jsonPath("$.result.lps[*].ubbM", everyItem(closeTo(3.6, 0.001))));
    }

    @Test
    void run_fallsBackToFacilityLimitOutsideMatrix() throws Exception {
        // A classification the matrix does not carry, with no explicit per-LP limit, falls straight
        // to the facility's $25M default (which does not bind the $20M uncalled) — a non-empty
        // cls_conc_limit_defaults entry for the class is ignored.
        putClsConcDefaults("""
            {"Legacy Institutional": 5}
            """);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoLegacyPayload()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[*].uecM", everyItem(closeTo(20.0, 0.001))));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private void putClsConcDefaults(String json) throws Exception {
        mvc.perform(put("/api/config/eligibility")
                .param("section", "cls_conc_limit_defaults")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk());
    }

    /** Two AAA-rated US LPs ($20M uncalled each) with configurable per-LP limits. TEST ONLY */
    private static String twoRatedPayload(String ubsConc1, String ubsConc2) {
        return "{ \"lps\": [\n" + lpJson(1, "Rated Investor", "AAA", "Aaa", ubsConc1) + ",\n"
            + lpJson(2, "Rated Investor", "AAA", "Aaa", ubsConc2) + "\n] }";
    }

    /** Two LPs classified outside the criteria matrix ($20M uncalled each). TEST ONLY */
    private static String twoLegacyPayload() {
        return "{ \"lps\": [\n" + lpJson(1, "Legacy Institutional", "", "", null) + ",\n"
            + lpJson(2, "Legacy Institutional", "", "", null) + "\n] }";
    }

    private static String lpJson(int i, String cls, String sp, String mdy, String ubsConc) {
        return """
            {
              "name": "Chain LP %02d",
              "parent": null, "spv": false, "hq": true,
              "type": "Institutional", "region": "North America",
              "ig": true, "cls": "%s",
              "sp": "%s", "mdy": "%s", "fitch": "",
              "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
              "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": null,
              "uc": "$20.0M", "pctUncalled": null, "pctCalled": null,
              "agentConc": null, "ubsConc": %s,
              "agentRate": 0.95, "abb": "$19.0M",
              "inc": true, "rcl": false, "notes": null
            }""".formatted(i, cls, sp, mdy, ubsConc == null ? "null" : "\"" + ubsConc + "\"");
    }
}
