package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpRecord;

import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Round-trip coverage for the money columns on lp_records (uncalled_capital, cap_commit, aum,
 * agent_bb), which are NUMERIC — a single precise column per field, no display-string sibling.
 * The round trip is asserted at both boundaries: the write paths (service ingest, Shadow BB
 * commit) must persist exact dollars with no rounding or abbreviation ($12,345,678.90 must never
 * degrade to $12.3M), and the engine must compute from that exact value.
 */
class LpNumericRoundTripIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc              mvc;
    @Autowired FacilityRepository   facilityRepo;
    @Autowired LpRecordRepository         lpRecordRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Meridian Secondaries Fund II");    // TEST ONLY
        f.setAgentBank("Citibank");
        facilityId = facilityRepo.save(f).getId();
    }

    private LpRecord seedLp(String investorName, String cls) {
        LpRecord lpRecord = new LpRecord();
        lpRecord.setFacilityId(facilityId);
        lpRecord.setInvestorName(investorName);
        lpRecord.setInvestorSegmentOrType("Pension");
        lpRecord.setRegionLocation("US");
        lpRecord.setUbsLpCategory(cls);
        return lpRecordRepo.save(lpRecord);
    }

    // ── Extraction ingest: exact decimals persist, to the cent ──────────────────────────

    @Test
    @WithMockUser(username = "extraction-svc", roles = {"SERVICE"})
    void ingest_persistsExactDollarsWithoutRounding() throws Exception {
        seedLp("Acme Pension Fund", "Rated Investor");

        String body = """
            {
              "facilityId": %d,
              "extraction": {
                "template": {"format": "CITI_STD", "version": "1", "headerRowIndex": 0},
                "records": [{
                  "rowIndex": 0,
                  "investorName": {"value": "Acme Pension Fund", "confidence": 1.0, "sourceHeader": "Investor"},
                  "commitment":   {"value": 20000000.55,  "confidence": 1.0, "sourceHeader": "Commitment"},
                  "uncalled":     {"value": 12345678.90,  "confidence": 1.0, "sourceHeader": "Uncalled"},
                  "aum":          {"value": 4250000000,   "confidence": 1.0, "sourceHeader": "AUM"},
                  "requiresReview": false,
                  "warnings": []
                }],
                "totalFlagged": 0
              }
            }
            """.formatted(facilityId);

        mvc.perform(post("/api/lpRecords/ingest").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(1));

        // The exact extracted dollars land in the numeric columns — $12,345,678.90 is not $12.3M.
        LpRecord lpRecord = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).getFirst();
        assertThat(lpRecord.getUncalledCapital()).isEqualByComparingTo(new BigDecimal("12345678.90"));
        assertThat(lpRecord.getCapitalCommitment()).isEqualByComparingTo(new BigDecimal("20000000.55"));
        assertThat(lpRecord.getAum()).isEqualTo("$4.25B");   // VARCHAR display field — agent text kept verbatim

        // GET serves them as full-precision display strings for the UI.
        mvc.perform(get("/api/lpRecords").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].uncalledCapital").value("$12,345,678.9"))
            .andExpect(jsonPath("$[0].capitalCommitment").value("$20,000,000.55"))
            .andExpect(jsonPath("$[0].aum").value("$4,250,000,000"));
    }

    // ── Shadow BB commit: numerics re-derived from the submitted strings; the agent-reported
    // abb is not commit-settable and survives the run untouched ────────────────────────

    @Test
    void run_rederivesNumericFromCommittedStrings_andPreservesIngestAbb() throws Exception {
        LpRecord stale = seedLp("CalPERS", "Rated Investor");
        stale.setUncalledCapital(new BigDecimal("99999999.99"));   // TEST ONLY — stale prior-cycle value
        stale.setAgentBorrowingBase(new BigDecimal("55555555.00"));  // TEST ONLY — ingest-written, must survive
        lpRecordRepo.save(stale);

        String body = """
            {
              "lps": [{
                "name": "CalPERS",
                "parent": null, "spv": false, "hq": true,
                "type": "Institutional", "region": "North America",
                "ig": true, "cls": "Rated Investor",
                "sp": "AAA", "mdy": "Aaa", "fitch": "",
                "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                "capCommit": "$15.0M", "pctCapCommit": null, "calledCap": "$3.0M",
                "uc": "$12.0M", "pctUncalled": null, "pctCalled": null,
                "agent_conc_limit": "7.5%", "ubs_conc_limit": "$25.0M",
                "agentRate": 0.95,
                "inc": true, "rcl": false, "notes": null
              }]
            }
            """;

        // Rated LP, $12M uncalled, 90% BUSA rate, $25M conc limit → UBB = min(12,25)*0.9 = $10.8M.
        // 10.8 (not a $99.9M-derived figure) proves the engine read the freshly committed value.
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.totalUBB").value(closeTo(10.8, 0.0001)));

        LpRecord lpRecord = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).getFirst();
        assertThat(lpRecord.getUncalledCapital()).isEqualByComparingTo(new BigDecimal("12000000"));
        assertThat(lpRecord.getCapitalCommitment()).isEqualByComparingTo(new BigDecimal("15000000"));
        assertThat(lpRecord.getAum()).isEqualTo("$500.0B");  // VARCHAR display field
        // A dollar-denominated conc limit must survive the numeric column as absolute dollars.
        assertThat(lpRecord.getUbsConcentrationLimit()).isEqualByComparingTo(new BigDecimal("25000000"));
        // abb is engine-input provenance (ingest-written) — the run must not clear it.
        assertThat(lpRecord.getAgentBorrowingBase()).isEqualByComparingTo(new BigDecimal("55555555.00"));
    }
}
