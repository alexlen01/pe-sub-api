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
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

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

    private static int persistedId(Facility facility) {
        return Objects.requireNonNull(facility.getId(), "Persisted facility must have an id");
    }

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

        // Re-feed with a changed agent bank AND a flipped bank_status: the row is updated in place,
        // its agent-reported bankStatus refreshes, but the platform-owned status is NOT touched on
        // update — it keeps the "Active" value seeded from bank_status at creation.
        mvc.perform(post("/api/facilities/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + FACILITY_ROW.replace("Northbank Agent", "Southbank Agent")
                                            .replace("\"bankStatus\": \"Active\"", "\"bankStatus\": \"Inactive\"") + "]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(0))
            .andExpect(jsonPath("$.updated").value(1));

        Facility saved = facilityRepo.findByName("Seed Facility Alpha").orElseThrow();
        assertThat(saved.getAgentBank()).isEqualTo("Southbank Agent");
        assertThat(saved.getAccountNumber()).isEqualTo("AC-1001");
        assertThat(saved.getLoanAmount()).isEqualByComparingTo(new BigDecimal("250000000.00"));
        // Platform status was seeded "Active" from the create-time bank_status and survives the
        // update, even though the update's bank_status was "Inactive".
        assertThat(saved.getStatus()).isEqualTo("Active");
        assertThat(saved.getBankStatus()).isEqualTo("Inactive");
        assertThat(saved.getConcLimitM()).isEqualByComparingTo(new BigDecimal("25"));
        // collateral_date round-trips from the feed. The extract wires BBDate -> collateral_date
        // (D7: label unchanged), so this is the column that carries "Last BB Run Date".
        assertThat(saved.getCollateralDate()).isEqualTo(LocalDate.parse("2026-05-31"));
    }

    @Test
    void facilityIngest_seedsPlatformStatusFromBankStatus_onCreate() throws Exception {
        // The extract marks orphan "Unknown"-bank placeholder facilities bank_status = Inactive.
        // Those must seed with platform status Inactive (LP Master shows them Inactive); a normal
        // Active-bank facility seeds Active.
        String inactiveRow = FACILITY_ROW
                .replace("Seed Facility Alpha", "Unknown Placeholder Facility")
                .replace("\"agentBank\": \"Northbank Agent\"", "\"agentBank\": \"Unknown\"")
                .replace("\"bankStatus\": \"Active\"", "\"bankStatus\": \"Inactive\"");

        mvc.perform(post("/api/facilities/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + FACILITY_ROW + ", " + inactiveRow + "]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(2));

        assertThat(facilityRepo.findByName("Seed Facility Alpha").orElseThrow().getStatus())
                .isEqualTo("Active");
        assertThat(facilityRepo.findByName("Unknown Placeholder Facility").orElseThrow().getStatus())
                .isEqualTo("Inactive");
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

        // Row 1 carries the full per-LP column set (row values must win over the LP Master
        // profile; "pension" is left blank to prove the blank->LP Master fallback). Rows 2-3
        // exercise the skip paths.
        String seedRows = """
            [
              { "facilityName": "Seed Facility Alpha", "investorName": "Acme Pension Fund",
                "capCommit": "$300M", "uncalled": "$90M", "agentCls": "Rated",
                "agentRate": "90%", "agentConc": "5%",
                "parent": "Row Parent Ltd", "spv": "TRUE", "highQty": "FALSE",
                "investorType": "Sovereign Wealth Fund", "instVsHnw": "Institutional",
                "regionLocation": "Norway", "investmentGrade": "FALSE",
                "ubsCls": "Unrated NAV > $1Bn", "sp": "A", "mdy": "A2", "fitch": "A-",
                "aum": "$7B", "nav": "$6B", "pension": "", "pensionFunded": "98%",
                "pctCapCommit": "3%", "calledCap": "$210M", "pctUncalled": "2.5%",
                "pctCalled": "70%", "ubsConc": "4%", "ubsRate": "75%",
                "agentBb": "$81M", "ubsBb": "$67.5M", "notes": "row note" },
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
        int facilityId = persistedId(facility);
        List<LpRecord> records = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId);
        assertThat(records).hasSize(1);
        LpRecord lp = records.getFirst();
        assertThat(lp.getLpMasterId()).isEqualTo(lpMasterRepo.findAll().getFirst().getId());
        assertThat(lp.getAgentCls()).isEqualTo("Rated Included");        // "Rated" normalized
        assertThat(lp.getAgentClsSource()).isEqualTo("EXTRACTED");
        assertThat(lp.getCapCommit()).isEqualTo("$300M");
        assertThat(lp.getUc()).isEqualTo("$90M");
        assertThat(lp.getAgentRate()).isEqualTo("90%");
        assertThat(lp.getAgentConc()).isEqualTo("5%");
        // Row values win over the LP Master profile:
        assertThat(lp.getCls()).isEqualTo("Unrated NAV > $1Bn");         // row ubsCls, not agentCls-derived
        assertThat(lp.getParent()).isEqualTo("Row Parent Ltd");
        assertThat(lp.isSpv()).isTrue();
        assertThat(lp.isHighQty()).isFalse();
        assertThat(lp.getInvestorType()).isEqualTo("Sovereign Wealth Fund");
        assertThat(lp.getInstVsHnw()).isEqualTo("Institutional");
        assertThat(lp.getRegionLocation()).isEqualTo("Norway");
        assertThat(lp.isIg()).isFalse();
        assertThat(lp.getSp()).isEqualTo("A");
        assertThat(lp.getMdy()).isEqualTo("A2");
        assertThat(lp.getFitch()).isEqualTo("A-");
        assertThat(lp.getAum()).isEqualTo("$7B");
        assertThat(lp.getNav()).isEqualTo("$6B");
        assertThat(lp.getPensionFunded()).isEqualTo("98%");
        // Blank row value falls back to the LP Master golden profile:
        assertThat(lp.getPension()).isEqualTo("$10B");
        // Row-only fields (no LP Master counterpart) round-trip:
        assertThat(lp.getPctCapCommit()).isEqualTo("3%");
        assertThat(lp.getCalledCap()).isEqualTo("$210M");
        assertThat(lp.getPctUncalled()).isEqualTo("2.5%");
        assertThat(lp.getPctCalled()).isEqualTo("70%");
        assertThat(lp.getUbsConc()).isEqualTo("4%");
        assertThat(lp.getUbsRate()).isEqualTo("75%");
        assertThat(lp.getAbb()).isEqualTo("$81M");
        assertThat(lp.getUbb()).isEqualTo("$67.5M");
        assertThat(lp.getNotes()).isEqualTo("row note");

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

        assertThat(lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId))
            .hasSize(1)
            .first()
            .satisfies(unchanged -> assertThat(unchanged.getCapCommit()).isEqualTo("$300M"));
    }

    @Test
    void lpRecordSeed_canonicalUbsClassificationsPassThroughUnchanged() throws Exception {
        mvc.perform(post("/api/facilities/ingest")
                .contentType(MediaType.APPLICATION_JSON).content("[" + FACILITY_ROW + "]"))
            .andExpect(status().isOk());
        // Three investors carrying the taxonomy classes added in V1_3 (Corp Pension > $1Bn,
        // HNW Feeder, HNW) — previously the normalizer had no case for them and remapped them
        // off the agent category, so no seeded record could ever carry these classes.
        mvc.perform(post("/api/lp-master/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + LP_MASTER_ROW.replace("Acme Pension Fund", "CP1 Pension Fund") + ","
                             + LP_MASTER_ROW.replace("Acme Pension Fund", "Feeder Family Office") + ","
                             + LP_MASTER_ROW.replace("Acme Pension Fund", "HNW Family Office") + "]"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/lpRecords/seed")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    [
                      { "facilityName": "Seed Facility Alpha", "investorName": "CP1 Pension Fund",
                        "capCommit": "$50M", "uncalled": "$20M", "agentCls": "Non-Rated Included",
                        "agentRate": "75%", "agentConc": "5%", "ubsCls": "Corp Pension > $1Bn Assets" },
                      { "facilityName": "Seed Facility Alpha", "investorName": "Feeder Family Office",
                        "capCommit": "$50M", "uncalled": "$20M", "agentCls": "Designated PWM",
                        "agentRate": "50%", "agentConc": "1%", "ubsCls": "HNW Feeder (acceptable)" },
                      { "facilityName": "Seed Facility Alpha", "investorName": "HNW Family Office",
                        "capCommit": "$50M", "uncalled": "$20M", "agentCls": "Designated PWM",
                        "agentRate": "50%", "agentConc": "1%", "ubsCls": "HNW (acceptable)" }
                    ]
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(3));

        Facility facility = facilityRepo.findByName("Seed Facility Alpha").orElseThrow();
        int facilityId = persistedId(facility);
        List<LpRecord> records = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId);
        assertThat(records).satisfiesExactly(
            cp1 -> {
                assertThat(cp1.getInvestorName()).isEqualTo("CP1 Pension Fund");
                assertThat(cp1.getCls()).isEqualTo("Corp Pension > $1Bn Assets");
            },
            feeder -> {
                assertThat(feeder.getInvestorName()).isEqualTo("Feeder Family Office");
                assertThat(feeder.getCls()).isEqualTo("HNW Feeder (acceptable)");
            },
            hnw -> {
                assertThat(hnw.getInvestorName()).isEqualTo("HNW Family Office");
                assertThat(hnw.getCls()).isEqualTo("HNW (acceptable)");
            });
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
