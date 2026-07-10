package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The bank-wide LP Master ({@code lp_master}) must capture each LP's finalized UBS credit profile
 * (classification, advance rate, concentration limit) from every point a facility's LP records
 * settle: a Shadow BB <b>Run</b>, a <b>Re-Run</b> (recompute with no payload), and a manual
 * <b>LP Category &amp; Rate</b> save flush. This test pins all three write-back paths so the
 * matrix-resolved values shown in Shadow BB are never silently dropped from LP Master.
 */
class LpMasterWriteBackIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc            mvc;
    @Autowired FacilityRepository facilityRepo;
    @Autowired LpMasterRepository lpMasterRepo;

    private static final String LP = "Blue Owl GP Stakes V (A) LP (Junior)";   // TEST ONLY

    private int facilityId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Apex Growth Fund V");   // TEST ONLY
        f.setAgentBank("Morgan Stanley");
        facilityId = facilityRepo.save(f).getId();
    }

    /** A single Rated LP carrying a matrix-resolved 90% rate / 7.5% conc limit. TEST ONLY.
     *  The LP name is injected via a literal replace (not String.format) so the payload's many
     *  literal '%' signs are left untouched. */
    private static String ratedLpPayload() {
        return """
            {
              "lps": [{
                "name": "__LP__",
                "parent": null, "spv": false, "hq": true,
                "type": "Institutional", "region": "North America",
                "ig": true, "cls": "Rated Investor",
                "sp": "AAA", "mdy": "Aaa", "fitch": "",
                "aum": "$500.0B", "nav": null, "pension": null, "pensionFunded": null,
                "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": "$14.0M",
                "uc": "$20.0M", "pctUncalled": null, "pctCalled": null,
                "agentConc": "7.5%", "ubsConc": "7.5%", "ubsRate": "90%",
                "agentRate": "95.0%", "abb": "$19.0M",
                "inc": true, "rcl": false, "notes": null
              }]
            }
            """.replace("__LP__", LP);
    }

    // ── Path 1: Run Shadow BB writes the resolved UBS profile to LP Master ────────────

    @Test
    void run_writesResolvedUbsRateAndConcToLpMaster() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ratedLpPayload()))
            .andExpect(status().isCreated());

        LpMaster m = lpMasterRepo.findByInvestorName(LP).orElseThrow();
        assertThat(m.getUbsClassification()).isEqualTo("Rated Investor");
        assertThat(m.getUbsDefaultAdvRate()).isEqualTo("90%");     // regression: previously dropped
        assertThat(m.getUbsDefaultConcLimit()).isEqualTo("7.5%");
    }

    // ── Path 2: Re-Run (no body) re-syncs LP Master from the persisted LP records ──────

    @Test
    void reRunWithoutBodyReSyncsLpMaster() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ratedLpPayload()))
            .andExpect(status().isCreated());

        // Simulate LP Master drifting out of sync (e.g. a stale earlier value).
        LpMaster m = lpMasterRepo.findByInvestorName(LP).orElseThrow();
        m.setUbsDefaultAdvRate("1%");
        m.setUbsDefaultConcLimit("1%");
        lpMasterRepo.save(m);

        // A Re-Run carries no payload — it only recomputes. It must still refresh LP Master.
        mvc.perform(post("/api/bb/run/{id}", facilityId))
            .andExpect(status().isCreated());

        LpMaster refreshed = lpMasterRepo.findByInvestorName(LP).orElseThrow();
        assertThat(refreshed.getUbsDefaultAdvRate()).isEqualTo("90%");
        assertThat(refreshed.getUbsDefaultConcLimit()).isEqualTo("7.5%");
    }

    // ── Path 3: Manual LP Category & Rate save flush writes LP Master ─────────────────

    @Test
    void manualClassificationFlushWritesLpMaster() throws Exception {
        // Seed the facility record (and its LP Master row) via a run.
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ratedLpPayload()))
            .andExpect(status().isCreated());

        // Reclassify + re-rate the LP, then flush with audit=true (the "leaving the screen" save).
        String patch = """
            {
              "facilityId": %d,
              "audit": true,
              "rows": [{
                "name": "%s", "originalName": "%s",
                "cls": "Other Institutional",
                "ubsAdvRatePct": 50, "ubsConcLimitPct": 5
              }]
            }
            """.formatted(facilityId, LP, LP);

        mvc.perform(patch("/api/lpRecords/classification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(patch))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(1));

        LpMaster m = lpMasterRepo.findByInvestorName(LP).orElseThrow();
        assertThat(m.getUbsClassification()).isEqualTo("Other Institutional");
        assertThat(m.getUbsDefaultAdvRate()).isEqualTo("50%");
        assertThat(m.getUbsDefaultConcLimit()).isEqualTo("5%");
    }

    @Test
    void silentClassificationSaveDoesNotWriteLpMaster() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ratedLpPayload()))
            .andExpect(status().isCreated());

        // A per-keystroke auto-save omits audit → the facility record updates, but LP Master must
        // not be re-synced on every keystroke (that happens only on the audited flush).
        String patch = """
            {
              "facilityId": %d,
              "rows": [{
                "name": "%s", "originalName": "%s",
                "cls": "Other Institutional",
                "ubsAdvRatePct": 50, "ubsConcLimitPct": 5
              }]
            }
            """.formatted(facilityId, LP, LP);

        mvc.perform(patch("/api/lpRecords/classification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(patch))
            .andExpect(status().isOk());

        LpMaster m = lpMasterRepo.findByInvestorName(LP).orElseThrow();
        assertThat(m.getUbsClassification()).isEqualTo("Rated Investor");   // unchanged
        assertThat(m.getUbsDefaultAdvRate()).isEqualTo("90%");
    }
}
