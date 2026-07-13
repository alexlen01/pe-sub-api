package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.ReportHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc                 mvc;
    @Autowired FacilityRepository      facilityRepo;
    @Autowired BbSnapshotRepository    snapshotRepo;
    @Autowired ReportHistoryRepository historyRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Apex Growth Fund IV");    // TEST ONLY
        f.setAgentBank("Wells Fargo");
        facilityId = facilityRepo.save(f).getId();
    }

    // ── Collateral & Coverage certificate ────────────────────────────────────────

    @Test
    void collateral_returnsCertificateDataFromLatestSnapshot() throws Exception {
        runBb(facilityId);

        mvc.perform(get("/api/reports/collateral/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.facilityId").value(facilityId))
            .andExpect(jsonPath("$.facilityName").value("Apex Growth Fund IV"))
            .andExpect(jsonPath("$.agentBank").value("Wells Fargo"))
            .andExpect(jsonPath("$.snapshotId").isNumber())
            .andExpect(jsonPath("$.calculatedAt").isString())
            .andExpect(jsonPath("$.summary.totalUBB").isNumber())
            .andExpect(jsonPath("$.summary.totalABB").isNumber())
            .andExpect(jsonPath("$.summary.ear").isNumber())
            .andExpect(jsonPath("$.totalEligibleUncalledM").isNumber())
            // 3-LP payload: one Rated Investor, one Unrated NAV, one Excluded
            .andExpect(jsonPath("$.classBreakdown[*].cls",
                hasItems("Rated Investor", "Unrated NAV > $1Bn", "Excluded")))
            .andExpect(jsonPath("$.classBreakdown[0].cls").value("Rated Investor"))
            .andExpect(jsonPath("$.classBreakdown[0].count").value(1))
            .andExpect(jsonPath("$.classBreakdown[0].uncalledM").value(closeTo(20.0, 0.01)))
            .andExpect(jsonPath("$.classBreakdown[0].ubbM").isNumber())
            .andExpect(jsonPath("$.classBreakdown[0].rate").value("90%"));
    }

    @Test
    void collateral_acceptsExplicitSnapshotId() throws Exception {
        runBb(facilityId);
        int snapshotId = snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .orElseThrow().getId();

        mvc.perform(get("/api/reports/collateral/{id}", facilityId)
                .param("snapshotId", String.valueOf(snapshotId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.snapshotId").value(snapshotId));
    }

    @Test
    void collateralPdf_returnsStyledPdfDownload() throws Exception {
        runBb(facilityId);
        byte[] body = mvc.perform(get("/api/reports/collateral/{id}/pdf", facilityId).param("watermark", "FINAL"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", containsString("bb-certificate-apex-growth-fund-iv.pdf")))
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).startsWith("%PDF-".getBytes());
        assertThat(body.length).isGreaterThan(1_000);
    }

    @Test
    void collateralPdf_ignoresSnapshotsWithoutSummaryInTrend() throws Exception {
        runBb(facilityId);

        BbSnapshot incomplete = new BbSnapshot();
        incomplete.setFacilityId(facilityId);
        incomplete.setResult(new BbResult(List.of(), null, List.of()));
        snapshotRepo.save(incomplete);

        byte[] body = mvc.perform(get("/api/reports/collateral/{id}/pdf", facilityId)
                .param("snapshotId", String.valueOf(incomplete.getId()))
                .param("coverageTrend", "true"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).startsWith("%PDF-".getBytes());
    }

    @Test
    void collateral_returns404WhenNoSnapshotExists() throws Exception {
        mvc.perform(get("/api/reports/collateral/{id}", facilityId))
            .andExpect(status().isNotFound());
    }

    @Test
    void collateral_returns404ForUnknownFacility() throws Exception {
        mvc.perform(get("/api/reports/collateral/{id}", 999999))
            .andExpect(status().isNotFound());
    }

    @Test
    void collateral_returns404ForSnapshotOfOtherFacility() throws Exception {
        runBb(facilityId);
        int snapshotId = snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .orElseThrow().getId();

        Facility other = new Facility();
        other.setName("Other Fund");    // TEST ONLY
        other.setAgentBank("Citi");
        int otherId = facilityRepo.save(other).getId();

        mvc.perform(get("/api/reports/collateral/{id}", otherId)
                .param("snapshotId", String.valueOf(snapshotId)))
            .andExpect(status().isNotFound());
    }

    // ── Effective Advance Rates trend ─────────────────────────────────────────────

    @Test
    void ear_returnsOnePointPerSnapshotOldestFirst() throws Exception {
        runBb(facilityId);
        runBb(facilityId);

        mvc.perform(get("/api/reports/ear/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].calculatedAt").isString())
            .andExpect(jsonPath("$[0].ear").isNumber())
            .andExpect(jsonPath("$[0].agentEar").isNumber())
            .andExpect(jsonPath("$[0].earDelta").isNumber());

        assertThat(snapshotRepo.findByFacilityIdOrderByCalculatedAtAsc(facilityId)).hasSize(2);
    }

    @Test
    void ear_returnsEmptyListWhenNoSnapshots() throws Exception {
        mvc.perform(get("/api/reports/ear/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", empty()));
    }

    @Test
    void ear_returns404ForUnknownFacility() throws Exception {
        mvc.perform(get("/api/reports/ear/{id}", 999999))
            .andExpect(status().isNotFound());
    }

    // ── Agent Bank Exposure ───────────────────────────────────────────────────────

    @Test
    void agentBanks_aggregatesLatestSnapshotsPerBank() throws Exception {
        runBb(facilityId);

        Facility noRun = new Facility();
        noRun.setName("Citi Fund Without Run");    // TEST ONLY
        noRun.setAgentBank("Citi");
        facilityRepo.save(noRun);

        mvc.perform(get("/api/reports/agent-banks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            // Sorted by UBS BB desc — Wells Fargo (with a snapshot) first
            .andExpect(jsonPath("$[0].agentBank").value("Wells Fargo"))
            .andExpect(jsonPath("$[0].facilityCount").value(1))
            .andExpect(jsonPath("$[0].lpCount").value(3))
            .andExpect(jsonPath("$[0].ubsBBM").value(greaterThan(0.0)))
            .andExpect(jsonPath("$[0].agentBBM").value(greaterThan(0.0)))
            .andExpect(jsonPath("$[0].deltaM").isNumber())
            // Facility without a snapshot still appears, with zero exposure
            .andExpect(jsonPath("$[1].agentBank").value("Citi"))
            .andExpect(jsonPath("$[1].facilityCount").value(1))
            .andExpect(jsonPath("$[1].lpCount").value(0))
            .andExpect(jsonPath("$[1].ubsBBM").value(0.0));
    }

    // ── Concentration Exposures ───────────────────────────────────────────────────

    @Test
    void concentration_returnsBreachesFromLatestSnapshot() throws Exception {
        runBb(facilityId);

        // CalPERS holds ~2/3 of UBS BB → the single-LP (15%) and top-10 (60%) tests both trip.
        mvc.perform(get("/api/reports/concentration/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.breaches").isArray())
            .andExpect(jsonPath("$.breaches", not(empty())))
            .andExpect(jsonPath("$.breaches[0].type").isString())
            .andExpect(jsonPath("$.breaches[0].severity").isString())
            .andExpect(jsonPath("$.breaches[0].message").isString());
    }

    @Test
    void concentration_returnsEmptyBreachesWhenNoSnapshotExists() throws Exception {
        mvc.perform(get("/api/reports/concentration/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.breaches").isArray())
            .andExpect(jsonPath("$.breaches", empty()));
    }

    // ── Report history ────────────────────────────────────────────────────────────

    @Test
    void history_postThenGetRoundTrips() throws Exception {
        mvc.perform(post("/api/reports/history")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "report": "Collateral & Coverage",
                      "facilityId": %d,
                      "snapshotLabel": "May 2026",
                      "format": "PDF"
                    }
                    """.formatted(facilityId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.report").value("Collateral & Coverage"))
            .andExpect(jsonPath("$.facilityId").value(facilityId))
            .andExpect(jsonPath("$.facilityName").value("Apex Growth Fund IV"))
            .andExpect(jsonPath("$.snapshotLabel").value("May 2026"))
            .andExpect(jsonPath("$.format").value("PDF"))
            .andExpect(jsonPath("$.createdAt").isString());

        mvc.perform(get("/api/reports/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].report").value("Collateral & Coverage"))
            .andExpect(jsonPath("$[0].facilityName").value("Apex Growth Fund IV"));

        assertThat(historyRepo.findAll()).hasSize(1);
    }

    @Test
    void history_postWithoutFacilityIsAllowed() throws Exception {
        // Portfolio-wide reports (e.g. Agent Bank Exposure) have no single facility.
        mvc.perform(post("/api/reports/history")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "report": "Agent Bank Exposure", "format": "XLSX" }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.facilityId").value(nullValue()))
            .andExpect(jsonPath("$.facilityName").value(nullValue()));
    }

    @Test
    void history_postWithoutReportReturns400() throws Exception {
        mvc.perform(post("/api/reports/history")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"format\": \"PDF\" }"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void history_postWithUnknownFacilityReturns404() throws Exception {
        mvc.perform(post("/api/reports/history")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"report\": \"Collateral & Coverage\", \"facilityId\": 999999 }"))
            .andExpect(status().isNotFound());
    }

    // ── Helper ────────────────────────────────────────────────────────────────────

    /** Runs a Shadow BB with a 3-LP payload (Rated Investor / Unrated NAV / Excluded) to create a snapshot. */
    private void runBb(int facilityId) throws Exception {
        // TEST ONLY payload
        String body = """
            {
              "lps": [
                {
                  "name": "CalPERS",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": true, "cls": "Rated Investor",
                  "sp": "AAA", "mdy": "Aaa", "fitch": "",
                  "aum": "$500.0B", "nav": null, "pension": null, "pensionFunded": null,
                  "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": "$14.0M",
                  "uc": "$20.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "7.5%", "ubsConc": "$25.0M",
                  "agentRate": "95.0%", "abb": "$19.0M",
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "name": "Stanford Endowment",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": false, "cls": "Unrated NAV > $1Bn",
                  "sp": "", "mdy": "", "fitch": "",
                  "aum": "$40.0B", "nav": null, "pension": null, "pensionFunded": null,
                  "capCommit": "$10.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "7.5%", "ubsConc": "$25.0M",
                  "agentRate": "75.0%", "abb": "$7.5M",
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "name": "Tiny Fund LLC",
                  "parent": null, "spv": true, "hq": false,
                  "type": "HNW", "region": "Europe",
                  "ig": false, "cls": "Excluded",
                  "sp": "", "mdy": "", "fitch": "",
                  "aum": null, "nav": null, "pension": null, "pensionFunded": null,
                  "capCommit": "$1.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$1.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": null, "ubsConc": "$25.0M",
                  "agentRate": "0%", "abb": "$0",
                  "inc": false, "rcl": false, "notes": null
                }
              ]
            }
            """;

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }
}

