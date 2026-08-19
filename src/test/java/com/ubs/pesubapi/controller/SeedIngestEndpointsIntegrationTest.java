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
          "highQuality": true,
          "investorType": "Pension Fund",
          "institutionalOrHnw": "Institutional",
          "regionLocation": "United States",
          "investmentGrade": true,
          "spRating": "AA",
          "moodysRating": "Aa2",
          "fitchRating": "AA",
          "aum": "$10B",
          "nav": "$8B",
          "pensionAssets": "$10B",
          "fundingRatio": "105%",
          "ubsLpCategory": null,
          "ubsDefaultAdvanceRate": "90%",
          "ubsDefaultConcentrationLimit": "5%",
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
        assertThat(all.getFirst().getSpRating()).isEqualTo("AA");
        assertThat(all.getFirst().isInvestmentGrade()).isTrue();
    }

    @Test
    void lpMasterIngest_withoutHighQuality_keepsSchemaDefaultRatherThanFalse() throws Exception {
        // The 2026-08-18 LP DB Export dropped the High Quality column, so the feed omits the field
        // entirely. It must land on the schema default (TRUE) — as a primitive it would deserialise
        // to false, quietly flipping every LP out of the high-quality tier and firing the aggregate
        // breach checks in BbCalculationService that key off it.
        String noHighQuality = LP_MASTER_ROW.replace("  \"highQuality\": true,\n", "");
        assertThat(noHighQuality).doesNotContain("highQuality");

        mvc.perform(post("/api/lp-master/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + noHighQuality + "]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1));

        assertThat(lpMasterRepo.findAll().getFirst().isHighQuality()).isTrue();
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
        // profile; "pensionAssets" is left blank to prove the blank->LP Master fallback). Rows 2-3
        // exercise the skip paths.
        String seedRows = """
            [
              { "facilityName": "Seed Facility Alpha", "investorName": "Acme Pension Fund",
                "capitalCommitment": "$300M", "uncalledCapital": "$90M", "agentLpCategory": "Rated",
                "agentAdvanceRate": "90%", "agentConcentrationLimit": "5%",
                "parent": "Row Parent Ltd", "spv": "TRUE",
                "investorType": "Sovereign Wealth Fund", "institutionalOrHnw": "Institutional",
                "regionLocation": "Norway", "investmentGrade": "FALSE",
                "ubsLpCategory": "Unrated NAV > $1Bn", "spRating": "A", "moodysRating": "A2", "fitchRating": "A-",
                "aum": "$7B", "nav": "$6B", "pensionAssets": "", "fundingRatio": "98%",
                "pctOfFundCommitments": "3%", "calledCapital": "$210M", "pctOfFundUncalled": "2.5%",
                "pctLpCalled": "70%", "ubsConcentrationLimit": "4%", "ubsAdvanceRate": "75%",
                "agentExcessConcentration": "$12M", "ubsExcessConcentration": "$9M",
                "agentBorrowingBase": "$81M", "ubsBorrowingBase": "$67.5M", "notes": "row note" },
              { "facilityName": "No Such Facility", "investorName": "Acme Pension Fund",
                "capitalCommitment": "$1M", "uncalledCapital": "$1M", "agentLpCategory": "Rated",
                "agentAdvanceRate": "90%", "agentConcentrationLimit": "5%" },
              { "facilityName": "Seed Facility Alpha", "investorName": "Unknown Investor",
                "capitalCommitment": "$1M", "uncalledCapital": "$1M", "agentLpCategory": "Rated",
                "agentAdvanceRate": "90%", "agentConcentrationLimit": "5%" }
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
        assertThat(lp.getAgentLpCategory()).isEqualTo("Rated Included");        // "Rated" normalized
        assertThat(lp.getAgentLpCategorySource()).isEqualTo("EXTRACTED");
        assertThat(lp.getCapitalCommitment()).isEqualByComparingTo("300000000");
        assertThat(lp.getUncalledCapital()).isEqualByComparingTo("90000000");
        assertThat(lp.getAgentAdvanceRate()).isEqualByComparingTo("0.90");
        assertThat(lp.getAgentConcentrationLimit()).isEqualByComparingTo("5");
        // Row values win over the LP Master profile:
        assertThat(lp.getUbsLpCategory()).isEqualTo("Unrated NAV > $1Bn");         // row ubsCls, not agentCls-derived
        assertThat(lp.getParent()).isEqualTo("Row Parent Ltd");
        assertThat(lp.isSpv()).isTrue();
        // Not a feed column any more: inherited from the LP Master profile, which is true here.
        assertThat(lp.isHighQuality()).isTrue();
        assertThat(lp.getInvestorType()).isEqualTo("Sovereign Wealth Fund");
        assertThat(lp.getInstitutionalOrHnw()).isEqualTo("Institutional");
        assertThat(lp.getRegionLocation()).isEqualTo("Norway");
        assertThat(lp.isInvestmentGrade()).isFalse();
        assertThat(lp.getSpRating()).isEqualTo("A");
        assertThat(lp.getMoodysRating()).isEqualTo("A2");
        assertThat(lp.getFitchRating()).isEqualTo("A-");
        assertThat(lp.getAum()).isEqualTo("$7B");
        assertThat(lp.getAgentExcessConcentration()).isEqualByComparingTo("12000000");
        assertThat(lp.getUbsExcessConcentration()).isEqualByComparingTo("9000000");
        assertThat(lp.getNav()).isEqualTo("$6B");
        assertThat(lp.getFundingRatio()).isEqualByComparingTo("0.98");
        // Blank row value falls back to the LP Master golden profile:
        assertThat(lp.getPensionAssets()).isEqualTo("$10B");
        // Row-only fields (no LP Master counterpart) round-trip:
        assertThat(lp.getPctOfFundCommitments()).isEqualByComparingTo("0.03");
        assertThat(lp.getCalledCapital()).isEqualByComparingTo("210000000");
        assertThat(lp.getPctOfFundUncalled()).isEqualByComparingTo("0.025");
        assertThat(lp.getPctLpCalled()).isEqualByComparingTo("0.70");
        assertThat(lp.getUbsConcentrationLimit()).isEqualByComparingTo("4");
        assertThat(lp.getUbsAdvanceRate()).isEqualByComparingTo("0.75");
        assertThat(lp.getAgentBorrowingBase()).isEqualByComparingTo("81000000");
        assertThat(lp.getUbsBorrowingBase()).isEqualByComparingTo("67500000");
        assertThat(lp.getNotes()).isEqualTo("row note");

        // Re-seeding the same pair is a no-op: existing records are never overwritten.
        mvc.perform(post("/api/lpRecords/seed")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    [{ "facilityName": "Seed Facility Alpha", "investorName": "Acme Pension Fund",
                       "capitalCommitment": "$999M", "uncalledCapital": "$999M", "agentLpCategory": "Designated",
                       "agentAdvanceRate": "50%", "agentConcentrationLimit": "1%" }]
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(0))
            .andExpect(jsonPath("$.skipped").value(1));

        assertThat(lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId))
            .hasSize(1)
            .first()
            .satisfies(unchanged -> assertThat(unchanged.getCapitalCommitment()).isEqualByComparingTo("300000000"));
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
                        "capitalCommitment": "$50M", "uncalledCapital": "$20M", "agentLpCategory": "Non-Rated Included",
                        "agentAdvanceRate": "75%", "agentConcentrationLimit": "5%", "ubsLpCategory": "Corp Pension > $1Bn Assets" },
                      { "facilityName": "Seed Facility Alpha", "investorName": "Feeder Family Office",
                        "capitalCommitment": "$50M", "uncalledCapital": "$20M", "agentLpCategory": "Designated PWM",
                        "agentAdvanceRate": "50%", "agentConcentrationLimit": "1%", "ubsLpCategory": "HNW Feeder (acceptable)" },
                      { "facilityName": "Seed Facility Alpha", "investorName": "HNW Family Office",
                        "capitalCommitment": "$50M", "uncalledCapital": "$20M", "agentLpCategory": "Designated PWM",
                        "agentAdvanceRate": "50%", "agentConcentrationLimit": "1%", "ubsLpCategory": "HNW (acceptable)" }
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
                assertThat(cp1.getUbsLpCategory()).isEqualTo("Corp Pension > $1Bn Assets");
            },
            feeder -> {
                assertThat(feeder.getInvestorName()).isEqualTo("Feeder Family Office");
                assertThat(feeder.getUbsLpCategory()).isEqualTo("HNW Feeder (acceptable)");
            },
            hnw -> {
                assertThat(hnw.getInvestorName()).isEqualTo("HNW Family Office");
                assertThat(hnw.getUbsLpCategory()).isEqualTo("HNW (acceptable)");
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
