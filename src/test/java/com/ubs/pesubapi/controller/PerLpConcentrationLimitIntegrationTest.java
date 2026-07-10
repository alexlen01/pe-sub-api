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
 * The per-LP concentration limit must resolve through the configured fallback chain:
 * explicit per-LP limit (dollar or percent of total uncalled) → classification default
 * from the {@code cls_conc_limit_defaults} config key (the map edited on the Config
 * screen next to the BUSA Advance Rate Schedule) → facility-level dollar limit.
 */
class ClsConcLimitDefaultIntegrationTest extends IntegrationTestBase {

    /** Mirrors the V1_3 seed so each test leaves the shared config cache as it found it. TEST ONLY */
    private static final String SEED_CLS_CONC_DEFAULTS = """
        {
          "Rated Investor": 20.0,
          "Unrated NAV > $1Bn": 15.0,
          "FoF & Other > $10Bn AUM": 10.0,
          "Corp Pension > $5Bn Assets": 12.5,
          "Other Institutional": 7.5,
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
    void run_classDefaultCapsLpsWithoutExplicitLimit() throws Exception {
        // Rated default tightened to 5%. Two Rated LPs at $20M uncalled each with no
        // per-LP limit → total uncalled $40M → cap $2M each → UBB $1.8M at the 90% rate.
        putClsConcDefaults("""
            {"Rated Investor": 5}
            """);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoLpPayload(null, null)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[*].uecM", everyItem(closeTo(2.0, 0.001))))
            .andExpect(jsonPath("$.result.lps[*].ubbM", everyItem(closeTo(1.8, 0.001))));
    }

    @Test
    void run_perLpPercentLimitBeatsClassDefault() throws Exception {
        // First LP carries an explicit 10% limit → $4M of the $40M total uncalled; the
        // second falls to the 5% Rated class default → $2M.
        putClsConcDefaults("""
            {"Rated Investor": 5}
            """);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoLpPayload("10%", null)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[?(@.name=='Chain LP 01')].uecM", contains(closeTo(4.0, 0.001))))
            .andExpect(jsonPath("$.result.lps[?(@.name=='Chain LP 02')].uecM", contains(closeTo(2.0, 0.001))));
    }

    @Test
    void run_perLpDollarLimitBeatsClassDefault() throws Exception {
        // An explicit, binding $4M dollar limit wins over the 5% class default ($2M of the
        // $40M total uncalled): uecM = min($20M, $4M) = $4M → ubbM = $3.6M at the 90% rate.
        // Also covers the plain per-LP conc-limit round-trip (payload → persisted → computed).
        putClsConcDefaults("""
            {"Rated Investor": 5}
            """);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoLpPayload("$4.0M", "$4.0M")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[*].uecM", everyItem(closeTo(4.0, 0.001))))
            .andExpect(jsonPath("$.result.lps[*].ubbM", everyItem(closeTo(3.6, 0.001))));
    }

    @Test
    void run_fallsBackToFacilityLimitWithoutClassDefault() throws Exception {
        // Empty map → no class default; LPs without per-LP limits use the facility's
        // $25M default, which does not bind the $20M uncalled.
        putClsConcDefaults("{}");

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoLpPayload(null, null)))
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

    /** Two identical Rated US LPs ($20M uncalled each) with configurable per-LP limits. TEST ONLY */
    private static String twoLpPayload(String ubsConc1, String ubsConc2) {
        return "{ \"lps\": [\n" + lpJson(1, ubsConc1) + ",\n" + lpJson(2, ubsConc2) + "\n] }";
    }

    private static String lpJson(int i, String ubsConc) {
        return """
            {
              "name": "Chain LP %02d",
              "parent": null, "spv": false, "hq": true,
              "type": "Institutional", "region": "North America",
              "ig": true, "cls": "Rated Investor",
              "sp": "AAA", "mdy": "Aaa", "fitch": "",
              "aum": "$500.0B", "nav": null, "pension": null, "pensionFunded": null,
              "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": null,
              "uc": "$20.0M", "pctUncalled": null, "pctCalled": null,
              "agentConc": null, "ubsConc": %s,
              "agentRate": "95%%", "abb": "$19.0M",
              "inc": true, "rcl": false, "notes": null
            }""".formatted(i, ubsConc == null ? "null" : "\"" + ubsConc + "\"");
    }
}

