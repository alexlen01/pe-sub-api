package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SuppressWarnings("null")
class BbRunIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc              mvc;
    @Autowired FacilityRepository   facilityRepo;
    @Autowired LpRepository         lpRepo;
    @Autowired BbSnapshotRepository snapshotRepo;
    @Autowired AuditLogRepository   auditLogRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        auditLogRepo.deleteAll();
        snapshotRepo.deleteAll();
        lpRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("Apex Growth Fund IV");    // TEST ONLY
        f.setAgentBank("Wells Fargo");
        facilityId = facilityRepo.save(f).getId();
    }

    // ── 3a–3d: Full BB run — LP population, classification, rates, calculation ──

    @Test
    void run_upsertsLpMasterAndSavesSnapshot() throws Exception {
        String body = lpPayload(facilityId);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.facilityId").value(facilityId))
            .andExpect(jsonPath("$.result.summary.totalUBB").isNumber())
            .andExpect(jsonPath("$.result.summary.totalABB").isNumber())
            .andExpect(jsonPath("$.result.lps", hasSize(3)));

        // LP Master must contain exactly the 3 submitted records
        assertThat(lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)).hasSize(3);

        // Snapshot must be retrievable from the /latest endpoint
        mvc.perform(get("/api/bb/snapshots/{id}/latest", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.summary.totalUBB").isNumber());
    }

    @Test
    void run_computesCorrectUbbForRatedLp() throws Exception {
        // One Rated LP: $10M uncalled, 90% rate, $25M conc limit → UBB = min(10,25)*0.9 = $9M
        String body = """
            {
              "lps": [{
                "investorName": "CalPERS",
                "parent": null, "spv": false, "highQty": true,
                "invType": "Institutional", "region": "North America",
                "ig": true, "cls": "Rated",
                "sp": "AAA", "mdy": "Aaa", "fitch": "",
                "aum": "$500.0B", "nav": null, "pension": null, "pensionFunded": null,
                "capCommit": "$10.0M", "pctCapCommit": null, "calledCap": "$7.0M",
                "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                "agentConc": "7.5%%", "ubsConc": "$25.0M",
                "agentRate": "95.0%%", "abb": "$9.5M",
                "inc": true, "rcl": false, "notes": null
              }]
            }
            """;

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.totalUBB").value(closeTo(9.0, 0.01)))
            .andExpect(jsonPath("$.result.lps[0].cls").value("Rated"))
            .andExpect(jsonPath("$.result.lps[0].ubbM").value(closeTo(9.0, 0.01)));
    }

    @Test
    void run_upsertIsIdempotent() throws Exception {
        String body = lpPayload(facilityId);

        // First run — inserts 3 LPs
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        assertThat(lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)).hasSize(3);

        // Second run with the same payload — must update, not duplicate
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        assertThat(lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)).hasSize(3);
    }

    @Test
    void run_withoutBodyStillComputesFromExistingLpMaster() throws Exception {
        // Seed one LP manually to simulate pre-existing LP Master data
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lpPayload(facilityId)))
            .andExpect(status().isCreated());

        // Now call with no body — should compute from the persisted LPs
        mvc.perform(post("/api/bb/run/{id}", facilityId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps", hasSize(3)));
    }

    @Test
    void run_returns404ForUnknownFacility() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", 999999)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lps\":[]}"))
            .andExpect(status().isNotFound());
    }

    // ── Summary-ext: all 5 tables populated ──────────────────────────────────────

    @Test
    void summaryExt_returns5PopulatedTablesAfterRun() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lpPayload(facilityId)))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/bb/summary-ext/{id}", facilityId))
            .andExpect(status().isOk())
            // LP Portfolio table
            .andExpect(jsonPath("$.totalLPs").value(3))
            .andExpect(jsonPath("$.totalAllUncalled").isNumber())
            // Borrowing Base table
            .andExpect(jsonPath("$.ubsBBRaw").isNumber())
            .andExpect(jsonPath("$.agentBBRaw").isNumber())
            // BUSA breakdown (Table 3)
            .andExpect(jsonPath("$.busaBreakdown", not(empty())))
            .andExpect(jsonPath("$.busaBreakdown[0].rate").isString())
            .andExpect(jsonPath("$.busaBreakdown[0].count").isNumber())
            // Agent breakdown (Table 4)
            .andExpect(jsonPath("$.agentBreakdown", not(empty())))
            .andExpect(jsonPath("$.agentBreakdown[0].rate").isString())
            // LP Classification breakdown (Table 5)
            .andExpect(jsonPath("$.clsBreakdown", not(empty())))
            .andExpect(jsonPath("$.clsBreakdown[0].label").isString());
    }

    @Test
    void summaryExt_returnsZeroTablesForEmptyFacility() throws Exception {
        mvc.perform(get("/api/bb/summary-ext/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalLPs").value(0.0))
            .andExpect(jsonPath("$.busaBreakdown", empty()))
            .andExpect(jsonPath("$.agentBreakdown", empty()))
            .andExpect(jsonPath("$.clsBreakdown", empty()));
    }

    // ── Per-LP concentration limit stored in ubsConc is used by re-computation ──

    @Test
    void run_perLpConcLimitRoundTrips() throws Exception {
        // LP has $5M uncalled, conc limit $4M → uecM = min(5,4) = 4; 90% rate → ubbM = 3.6
        String body = """
            {
              "lps": [{
                "investorName": "Ontario Teachers",
                "parent": null, "spv": false, "highQty": true,
                "invType": "Institutional", "region": "North America",
                "ig": true, "cls": "Rated",
                "sp": "AA", "mdy": "Aa2", "fitch": "",
                "aum": "$200.0B", "nav": null, "pension": null, "pensionFunded": null,
                "capCommit": "$5.0M", "pctCapCommit": null, "calledCap": null,
                "uc": "$5.0M", "pctUncalled": null, "pctCalled": null,
                "agentConc": null, "ubsConc": "$4.0M",
                "agentRate": "95.0%%", "abb": "$4.75M",
                "inc": true, "rcl": false, "notes": null
              }]
            }
            """;

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[0].ubbM").value(closeTo(3.6, 0.01)))
            .andExpect(jsonPath("$.result.lps[0].uecM").value(closeTo(4.0, 0.01)));
    }

    // ── Helper ────────────────────────────────────────────────────────────────────

    /** 3-LP payload: one Rated, one Unrated, one Excluded. TEST ONLY */
    private static String lpPayload(int facilityId) {
        return """
            {
              "lps": [
                {
                  "investorName": "CalPERS",
                  "parent": null, "spv": false, "highQty": true,
                  "invType": "Institutional", "region": "North America",
                  "ig": true, "cls": "Rated",
                  "sp": "AAA", "mdy": "Aaa", "fitch": "",
                  "aum": "$500.0B", "nav": null, "pension": null, "pensionFunded": null,
                  "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": "$14.0M",
                  "uc": "$20.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "7.5%%", "ubsConc": "$25.0M",
                  "agentRate": "95.0%%", "abb": "$19.0M",
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "investorName": "Stanford Endowment",
                  "parent": null, "spv": false, "highQty": true,
                  "invType": "Institutional", "region": "North America",
                  "ig": false, "cls": "Unrated >2bn",
                  "sp": "", "mdy": "", "fitch": "",
                  "aum": "$40.0B", "nav": null, "pension": null, "pensionFunded": null,
                  "capCommit": "$10.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "7.5%%", "ubsConc": "$25.0M",
                  "agentRate": "75.0%%", "abb": "$7.5M",
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "investorName": "Tiny Fund LLC",
                  "parent": null, "spv": true, "highQty": false,
                  "invType": "HNW", "region": "Europe",
                  "ig": false, "cls": "Excluded",
                  "sp": "", "mdy": "", "fitch": "",
                  "aum": null, "nav": null, "pension": null, "pensionFunded": null,
                  "capCommit": "$1.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$1.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": null, "ubsConc": "$25.0M",
                  "agentRate": "0%%", "abb": "$0",
                  "inc": false, "rcl": false, "notes": null
                }
              ]
            }
            """;
    }
}
