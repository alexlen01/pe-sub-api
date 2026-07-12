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
 * Round-trip coverage for the precise numeric money columns (C2: uncalled_capital_num,
 * cap_commit_num, aum_num, agent_bb_num on lp_records). The columns are internal to the BB
 * engine — never exposed on a DTO — so the round trip is asserted at both boundaries: the write
 * paths (service ingest, Shadow BB commit) must persist exact dollars alongside full-precision
 * display strings (dollar amounts are never rounded or abbreviated: "$12,345,678.9", not
 * "$12.3M"), and the engine must compute from the exact numeric value.
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
        lpRecord.setInvType("Pension");
        lpRecord.setRegion("US");
        lpRecord.setCls(cls);
        return lpRecordRepo.save(lpRecord);
    }

    // ── Extraction ingest: exact decimals persist; display strings keep every digit ─────

    @Test
    @WithMockUser(username = "extraction-svc", roles = {"SERVICE"})
    void ingest_dualWritesExactNumericAndFullPrecisionDisplay() throws Exception {
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

        // Numeric columns carry the exact extracted dollars; display strings keep every
        // digit — never rounded or abbreviated ($12,345,678.9 is not $12.3M).
        LpRecord lpRecord = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).getFirst();
        assertThat(lpRecord.getUcNum()).isEqualByComparingTo(new BigDecimal("12345678.90"));
        assertThat(lpRecord.getCapCommitNum()).isEqualByComparingTo(new BigDecimal("20000000.55"));
        assertThat(lpRecord.getAumNum()).isEqualByComparingTo(new BigDecimal("4250000000"));
        assertThat(lpRecord.getUc()).isEqualTo("$12,345,678.9");
        assertThat(lpRecord.getCapCommit()).isEqualTo("$20,000,000.55");
        assertThat(lpRecord.getAum()).isEqualTo("$4,250,000,000");

        // GET still serves the display strings the UI renders.
        mvc.perform(get("/api/lpRecords").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].uc").value("$12,345,678.9"))
            .andExpect(jsonPath("$[0].capCommit").value("$20,000,000.55"))
            .andExpect(jsonPath("$[0].aum").value("$4,250,000,000"));
    }

    // ── Shadow BB commit: numeric re-derived from the submitted strings, stale cleared ──

    @Test
    void run_rederivesNumericFromCommittedStrings_andClearsStale() throws Exception {
        LpRecord stale = seedLp("CalPERS", "Rated Investor");
        stale.setUc("$99.9M");
        stale.setUcNum(new BigDecimal("99999999.99"));   // TEST ONLY — stale prior-cycle value
        stale.setAbbNum(new BigDecimal("55555555.00"));  // TEST ONLY — must be cleared, abb blank
        lpRecordRepo.save(stale);

        String body = """
            {
              "lps": [{
                "name": "CalPERS",
                "parent": null, "spv": false, "hq": true,
                "type": "Institutional", "region": "North America",
                "ig": true, "cls": "Rated Investor",
                "sp": "AAA", "mdy": "Aaa", "fitch": "",
                "aum": "$500.0B", "nav": null, "pension": null, "pensionFunded": null,
                "capCommit": "$15.0M", "pctCapCommit": null, "calledCap": "$3.0M",
                "uc": "$12.0M", "pctUncalled": null, "pctCalled": null,
                "agentConc": "7.5%", "ubsConc": "$25.0M",
                "agentRate": "95.0%", "abb": "",
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
        assertThat(lpRecord.getUc()).isEqualTo("$12.0M");
        assertThat(lpRecord.getUcNum()).isEqualByComparingTo(new BigDecimal("12000000"));
        assertThat(lpRecord.getCapCommitNum()).isEqualByComparingTo(new BigDecimal("15000000"));
        assertThat(lpRecord.getAumNum()).isEqualByComparingTo(new BigDecimal("500000000000"));
        assertThat(lpRecord.getAbbNum()).isNull();
    }
}

