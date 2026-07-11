package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four bulk endpoints pe-sub-jobs routes its feeds through instead of writing SQL against
 * pe-sub-api's tables directly: facility ingest, LP Master ingest, LP record seed, and the
 * cls-conc-limit-defaults config merge. All four are SERVICE-gated (service-to-service only).
 */
@WithMockUser(username = "jobs-svc", roles = {"SERVICE"})
class SeedIngestEndpointsIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired FacilityRepository facilityRepo;
    @Autowired LpMasterRepository lpMasterRepo;
    @Autowired LpRecordRepository lpRecordRepo;

    private static final String FACILITY_ROW = """
        {
          "name": "Seed Facility Alpha",
          "agentBank": "Northbank Agent",
          "accountNumber": "AC-1001",
          "loanAmount": 250000000.00,
          "maturityDate": "2029-06-30",
          "bankStatus": "Active",
          "bankStatusDate": "2026-05-31",
          "ubsParticipation": 100000000.00,
          "collateralDate": "2026-05-31"
        }
        """;

    private static final String LP_MASTER_ROW = """
        {
          "investorName": "Acme Pension Fund",
          "parent": "Acme Holdings",
          "spv": false,
          "highQty": true,
          "investorType": "Pension Fund",
          "instVsHnw": "Institutional",
          "regionLocation": "United States",
          "investmentGrade": true,
          "sp": "AA",
          "mdy": "Aa2",
          "fitch": "AA",
          "aum": "$10B",
          "nav": "$8B",
          "pension": "$10B",
          "pensionFunded": "105%",
          "ubsClassification": null,
          "ubsDefaultAdvRate": "90%",
          "ubsDefaultConcLimit": "5%",
          "notes": "seed"
        }
        """;

    @Test
    void facilityIngest_upsertsByName_skipsBlankRows_preservesPlatformFields() throws Exception {
        mvc.perform(post("/api/facilities/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + FACILITY_ROW + ", {\"name\": \"\", \"agentBank\": \"X\"}]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.updated").value(0))
            .andExpect(jsonPath("$.skipped").value(1));

        // Re-feed with a changed agent bank: updated in place, platform-owned fields untouched.
        mvc.perform(post("/api/facilities/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + FACILITY_ROW.replace("Northbank Agent", "Southbank Agent") + "]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(0))
            .andExpect(jsonPath("$.updated").value(1));

        Facility saved = facilityRepo.findByName("Seed Facility Alpha").orElseThrow();
        assertThat(saved.getAgentBank()).isEqualTo("Southbank Agent");
        assertThat(saved.getAccountNumber()).isEqualTo("AC-1001");
        assertThat(saved.getLoanAmount()).isEqualByComparingTo(new BigDecimal("250000000.00"));
        assertThat(saved.getStatus()).isEqualTo("Not Started");
        assertThat(saved.getConcLimitM()).isEqualByComparingTo(new BigDecimal("25"));
    }

    @Test
    void lpMasterIngest_upsertsByInvestorName() throws Exception {
        mvc.perform(post("/api/lp-master/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + LP_MASTER_ROW + "]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1));

        mvc.perform(post("/api/lp-master/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + LP_MASTER_ROW.replace("$10B", "$12B") + "]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(0))
            .andExpect(jsonPath("$.updated").value(1));

        List<LpMaster> all = lpMasterRepo.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().getAum()).isEqualTo("$12B");
        assertThat(all.getFirst().getSp()).isEqualTo("AA");
        assertThat(all.getFirst().isIg()).isTrue();
    }

    @Test
    void lpRecordSeed_resolvesReferencesByName_insertsOnlyWhenAbsent() throws Exception {
        mvc.perform(post("/api/facilities/ingest")
                .contentType(MediaType.APPLICATION_JSON).content("[" + FACILITY_ROW + "]"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/lp-master/ingest")
                .contentType(MediaType.APPLICATION_JSON).content("[" + LP_MASTER_ROW + "]"))
            .andExpect(status().isOk());

        String seedRows = """
            [
              { "facilityName": "Seed Facility Alpha", "investorName": "Acme Pension Fund",
                "capCommit": "$300M", "uncalled": "$90M", "agentCls": "Rated",
                "agentRate": "90%", "agentConc": "5%" },
              { "facilityName": "No Such Facility", "investorName": "Acme Pension Fund",
                "capCommit": "$1M", "uncalled": "$1M", "agentCls": "Rated",
                "agentRate": "90%", "agentConc": "5%" },
              { "facilityName": "Seed Facility Alpha", "investorName": "Unknown Investor",
                "capCommit": "$1M", "uncalled": "$1M", "agentCls": "Rated",
                "agentRate": "90%", "agentConc": "5%" }
            ]
            """;

        mvc.perform(post("/api/lpRecords/seed")
                .contentType(MediaType.APPLICATION_JSON).content(seedRows))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.skipped").value(2));

        Facility facility = facilityRepo.findByName("Seed Facility Alpha").orElseThrow();
        List<LpRecord> records = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facility.getId());
        assertThat(records).hasSize(1);
        LpRecord lp = records.getFirst();
        assertThat(lp.getLpMasterId()).isEqualTo(lpMasterRepo.findAll().getFirst().getId());
        assertThat(lp.getAgentCls()).isEqualTo("Rated Included");        // "Rated" normalized
        assertThat(lp.getCls()).isEqualTo("Rated Investor");             // derived from agentCls
        assertThat(lp.getAgentClsSource()).isEqualTo("EXTRACTED");
        assertThat(lp.getCapCommit()).isEqualTo("$300M");
        assertThat(lp.getUc()).isEqualTo("$90M");
        assertThat(lp.getAgentRate()).isEqualTo("90%");
        assertThat(lp.getAgentConc()).isEqualTo("5%");
        assertThat(lp.getParent()).isEqualTo("Acme Holdings");           // merged from LP Master

        // Re-seeding the same pair is a no-op: existing records are never overwritten.
        mvc.perform(post("/api/lpRecords/seed")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    [{ "facilityName": "Seed Facility Alpha", "investorName": "Acme Pension Fund",
                       "capCommit": "$999M", "uncalled": "$999M", "agentCls": "Designated",
                       "agentRate": "50%", "agentConc": "1%" }]
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(0))
            .andExpect(jsonPath("$.skipped").value(1));

        assertThat(lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facility.getId()))
            .hasSize(1)
            .first()
            .satisfies(unchanged -> assertThat(unchanged.getCapCommit()).isEqualTo("$300M"));
    }

    @Test
    void clsConcLimitDefaults_mergeOverwritesFedKeysAndPreservesOthers() throws Exception {
        mvc.perform(patch("/api/config/cls-conc-limit-defaults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"Seed Test Class A\": 12.5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['Seed Test Class A']").value(12.5));

        // A second feed for a different class must preserve the first one (jsonb-merge semantics).
        mvc.perform(patch("/api/config/cls-conc-limit-defaults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"Seed Test Class B\": 7.5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['Seed Test Class A']").value(12.5))
            .andExpect(jsonPath("$.['Seed Test Class B']").value(7.5));

        // Re-feeding a class overwrites its entry while still preserving the others.
        mvc.perform(patch("/api/config/cls-conc-limit-defaults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"Seed Test Class A\": 9.0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['Seed Test Class A']").value(9.0))
            .andExpect(jsonPath("$.['Seed Test Class B']").value(7.5));

        mvc.perform(patch("/api/config/cls-conc-limit-defaults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }
}
