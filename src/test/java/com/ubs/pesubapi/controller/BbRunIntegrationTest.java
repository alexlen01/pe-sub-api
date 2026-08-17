package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;

import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BbRunIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc              mvc;
    @Autowired FacilityRepository   facilityRepo;
    @Autowired LpRecordRepository   lpRecordRepo;
    @Autowired SubmissionRepository submissionRepo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private int facilityId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Apex Growth Fund IV");    // TEST ONLY
        f.setAgentBank("Wells Fargo");
        facilityId = facilityRepo.save(f).getId();
    }

    // ── 3a–3d: Full BB run — LpRecord population, classification, rates, calculation ──

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
        assertThat(lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)).hasSize(3);

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
                "name": "CalPERS",
                "parent": null, "spv": false, "hq": true,
                "type": "Institutional", "region": "North America",
                "ig": true, "cls": "Rated Investor",
                "sp": "AAA", "mdy": "Aaa", "fitch": "",
                "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                "capCommit": "$10.0M", "pctCapCommit": null, "calledCap": "$7.0M",
                "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                "agentConc": "7.5%%", "ubsConc": "$25.0M",
                "agentRate": 0.95,
                "inc": true, "rcl": false, "notes": null
              }]
            }
            """;

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.totalUBB").value(closeTo(9.0, 0.01)))
            .andExpect(jsonPath("$.result.lps[0].ubsLpCategory").value("Rated Investor"))
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

        assertThat(lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)).hasSize(3);

        // Second run with the same payload — must update, not duplicate
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        assertThat(lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)).hasSize(3);
    }

    @Test
    void rerunForReview_createsReviewSubmissionWhenFacilityHasNoSubmissionHistory() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lpPayload(facilityId)))
            .andExpect(status().isCreated());

        assertThat(submissionRepo.findByFacilityIdOrderByCreatedAtDesc(facilityId)).isEmpty();

        mvc.perform(post("/api/submissions/facilities/{id}/rerun-for-review", facilityId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.snapshot.facilityId").value(facilityId))
            .andExpect(jsonPath("$.snapshot.result.summary.reclassCount").isNumber())
            .andExpect(jsonPath("$.submission.facilityId").value(facilityId))
            .andExpect(jsonPath("$.submission.status").value("Pending Review"))
            .andExpect(jsonPath("$.submission.wizardStep").value(6));

        assertThat(submissionRepo.findByFacilityIdOrderByCreatedAtDesc(facilityId))
            .singleElement()
            .satisfies(submission -> {
                assertThat(submission.getFileName()).isEqualTo("LP classification re-run");
                assertThat(submission.getSubmittedBy()).isNotBlank();
            });
        assertThat(facilityRepo.findById(facilityId).orElseThrow().getStatus())
            .isEqualTo("Pending Review");
    }

    @Test
    void run_doesNotClearStickyReclassifiedFlagFromStalePayload() throws Exception {
        String reclassifiedPayload = lpPayload(facilityId)
            .replaceFirst("\\\"rcl\\\": false", "\\\"rcl\\\": true");

        // First run of the wizard: there is no earlier Shadow BB for a category change to
        // invalidate, so a client-side rcl flag is ignored (see ReclassificationPolicy).
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reclassifiedPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.reclassCount").value(0));

        // Re-run after that snapshot exists: the same flag now sticks.
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reclassifiedPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.reclassCount").value(1));

        // A stale screen still carrying rcl=false must not erase the persisted status or the
        // status embedded in the new report/snapshot result.
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lpPayload(facilityId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.reclassCount").value(1))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'CalPERS')].reclassified").value(true));

        assertThat(lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).stream()
            .filter(lp -> "CalPERS".equals(lp.getInvestorName()))
            .findFirst()
            .orElseThrow()
            .isReclassified()).isTrue();
    }

    @Test
    void run_collapsesDuplicateNamesWithinPayload() throws Exception {
        // The same investor name appears twice in one payload (e.g. a doubled Agent BB).
        // It must collapse onto a single record — last value wins — not violate the
        // (facility_id, investor_name) unique constraint.
        String body = """
            {
              "lps": [
                {
                  "name": "CalPERS",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": true, "cls": "Rated Investor",
                  "sp": "AAA", "mdy": "Aaa", "fitch": "",
                  "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$20.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": null, "ubsConc": "$25.0M",
                  "agentRate": 0.95,
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "name": "CalPERS",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": true, "cls": "Excluded",
                  "sp": "AAA", "mdy": "Aaa", "fitch": "",
                  "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$30.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$30.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": null, "ubsConc": "$25.0M",
                  "agentRate": 0,
                  "inc": false, "rcl": false, "notes": null
                }
              ]
            }
            """;

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        var lps = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId);
        assertThat(lps).hasSize(1);
        assertThat(lps.getFirst().getUbsLpCategory()).isEqualTo("Excluded");   // last value wins
    }

    @Test
    void run_withoutBodyRecalculatesAndStoresRanksFromExistingLpRecords() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lpPayload(facilityId)))
            .andExpect(status().isCreated());

        var corrupted = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId);
        corrupted.forEach(lpRecord -> lpRecord.setLpRank(99));
        lpRecordRepo.saveAll(corrupted);

        mvc.perform(post("/api/bb/run/{id}", facilityId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps", hasSize(3)));

        var ranks = new java.util.HashMap<String, Integer>();
        lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)
            .forEach(lpRecord -> ranks.put(lpRecord.getInvestorName(), lpRecord.getLpRank()));
        assertThat(ranks.get("CalPERS")).isEqualTo(1);
        assertThat(ranks.get("Stanford Endowment")).isEqualTo(2);
        // Excluded LPs are ranked too: Rank reflects size position in the full population.
        assertThat(ranks.get("Tiny Fund LLC")).isEqualTo(3);
    }

    @Test
    void run_persistsLongExtractedTextValuesVerbatim() throws Exception {
        // Real Agent BB workbooks carry investor names, type labels and full dollar
        // amounts well past 50 characters; the schema's typed widths (free text 255,
        // money display 64, percents 50) must round-trip them untruncated and dollars
        // unrounded. Rates follow the platform convention of exactly one decimal ("71.1%").
        String longName = "AXA Fund Platform Private Equity S.C.A., SICAV-RAIF - Vintage 2025 Feeder";
        String longType = "Public Pension Fund - Governmental Plan (ERISA-exempt, non-US regulated)";
        String body = """
            {
              "lps": [{
                "name": "%s",
                "parent": null, "spv": false, "hq": true,
                "type": "%s", "investor_type": "%s", "region": "North America",
                "ig": true, "cls": "Rated Investor",
                "sp": "AAA", "mdy": "Aaa", "fitch": "",
                "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                "capCommit": "$12,102,000,000", "pctCapCommit": 0.488,
                "calledCap": "$7,013,456,789",
                "uc": "$12,372,297,594", "pctUncalled": 0.499,
                "pctCalled": 0.711,
                "agentConc": "7.5%%", "ubsConc": "$25.0M",
                "agentRate": 0.95,
                "inc": true, "rcl": false, "notes": null
              }]
            }
            """.formatted(longName, longType, longType);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        var lp = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).getFirst();
        assertThat(lp.getInvestorName()).isEqualTo(longName);
        assertThat(lp.getInvestorType()).isEqualTo(longType);
        assertThat(lp.getInstitutionalOrHnw()).isEqualTo(longType);   // JSON "type" aliases inst_vs_hnw
        assertThat(lp.getCapitalCommitment()).isEqualByComparingTo("12102000000");
        // Percent columns are NUMERIC fractions: the submitted 0.488 / 0.711 persist as-is.
        assertThat(lp.getPctOfFundCommitments()).isEqualByComparingTo("0.488");
        assertThat(lp.getPctLpCalled()).isEqualByComparingTo("0.711");
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
            .andExpect(jsonPath("$.pctUncalledGt25bnAum").isNumber())
            // Borrowing Base table
            .andExpect(jsonPath("$.ubsBBRaw").isNumber())
            .andExpect(jsonPath("$.agentBBRaw").isNumber())
            // BUSA breakdown (Table 3)
            .andExpect(jsonPath("$.busaBreakdown", not(empty())))
            .andExpect(jsonPath("$.busaBreakdown[0].rate").isString())
            .andExpect(jsonPath("$.busaBreakdown[0].count").isNumber())
            // Agent breakdown (Table 4)
            .andExpect(jsonPath("$.agentBreakdown", not(empty())))
            .andExpect(jsonPath("$.agentBreakdown[0].rate").value("90%"))
            .andExpect(jsonPath("$.agentBreakdown[*].rate", not(hasItem("95%"))))
            .andExpect(jsonPath("$.agentBreakdown[*].rate", not(hasItem("60%"))))
            // LP Category breakdown (Table 5)
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

    // ── per-LP concentration limit stored in ubsConc is used by re-computation ──

    // NOTE: the per-LP conc-limit round-trip (binding dollar limit caps uecM/ubbM) is
    // covered by PerLpConcentrationLimitIntegrationTest.run_perLpDollarLimitBeatsMatrix,
    // which exercises the same persist → compute path within the full resolution chain.

    // ── Facility list surfaces the latest snapshot's BB figures ─────────────────────

    @Test
    void facilityList_includesLatestShadowBbFigures() throws Exception {
        // Before any run the BB figures are null (no snapshot yet).
        mvc.perform(get("/api/facilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].ubsBB").value(nullValue()))
            .andExpect(jsonPath("$[0].agentBB").value(nullValue()));

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON).content(lpPayload(facilityId)))
            .andExpect(status().isCreated());

        // After a run the most recent snapshot's totals surface on the facility list & detail.
        mvc.perform(get("/api/facilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].agentBB").isNumber())
            .andExpect(jsonPath("$[0].ubsBB").isNumber())
            .andExpect(jsonPath("$[0].bbDelta").isNumber())
            .andExpect(jsonPath("$[0].ear").isNumber());

        mvc.perform(get("/api/facilities/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ubsBB").isNumber());
    }

    // ── UBS LP Category taxonomy resolves a non-zero advance rate ────────────────────

    @Test
    void run_computesUbbForUbsTaxonomyClass() throws Exception {
        // LpRecord classified under the UBS taxonomy ("FoF & Other > $10Bn AUM"), no stored per-LP
        // ubsRate. The bb_criteria_matrix now supplies the default: this LP is 0% funded
        // (called = commit − uncalled = 0), so it takes the FoF "< 40% funded" advance rate of 65%
        // (not the ≥40% mature 75%). uec = min(10, 25) = 10; 65% → ubbM = 6.5.
        String body = """
            {
              "lps": [{
                "name": "Blackstone FoF",
                "parent": null, "spv": false, "hq": false,
                "type": "Institutional", "region": "North America",
                "ig": false, "cls": "FoF & Other > $10Bn AUM",
                "sp": "", "mdy": "", "fitch": "",
                "aum": "$80.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                "capCommit": "$10.0M", "pctCapCommit": null, "calledCap": null,
                "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                "agentConc": null, "ubsConc": "$25.0M",
                "agentRate": 0.75,
                "inc": true, "rcl": false, "notes": null
              }]
            }
            """;

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.totalUBB").value(closeTo(6.5, 0.01)))
            .andExpect(jsonPath("$.result.lps[0].ubbM").value(closeTo(6.5, 0.01)));
    }

    @Test
    void summaryExt_derivesCalledCapitalAndCanonicalClassBuckets() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON).content(lpPayload(facilityId)))
            .andExpect(status().isCreated());

        // Called Capital: CalPERS stored $14M; Stanford/Tiny blank → commit−uncalled = 0 → total 14.
        // Classification buckets roll the granular labels up to the 4 canonical eligibility tiers.
        mvc.perform(get("/api/bb/summary-ext/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCalledCap").value(closeTo(14.0, 0.01)))
            .andExpect(jsonPath("$.clsBreakdown[*].label",
                hasItems("Rated Investors", "Unrated Investors", "Excluded Investors")));
    }

    @Test
    void summaryExt_populatesFacilityMetricsFromStoredInputs() throws Exception {
        Facility f = facilityRepo.findById(facilityId).orElseThrow();
        f.setFacilitySize(new java.math.BigDecimal("100000000"));        // $100M
        f.setUbsParticipation(new java.math.BigDecimal("50000000"));     // $50M
        facilityRepo.save(f);

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON).content(lpPayload(facilityId)))
            .andExpect(status().isCreated());

        // Stored dollars surface as $millions; participation rate = 50/100; available commitment =
        // MIN(facility size, agent BB). Agent BB is engine-derived (no payload abb): CalPERS
        // 2.325×0.95 + Stanford 2.325×0.75 = 3.9525 → MIN(100, 3.9525) = 3.9525.
        mvc.perform(get("/api/bb/summary-ext/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.facilitySize").value(closeTo(100.0, 0.01)))
            .andExpect(jsonPath("$.ubsParticipation").value(closeTo(50.0, 0.01)))
            .andExpect(jsonPath("$.ubsParticipationPct").value(closeTo(0.5, 0.001)))
            .andExpect(jsonPath("$.availableCommit").value(closeTo(3.9525, 0.01)))
            .andExpect(jsonPath("$.facilityAdvRate").isNumber());
    }

    // ── Server-authoritative snapshot: per-row shares, agent excess, extended summary ──

    @Test
    void run_snapshotCarriesPerRowSharesAndExtendedSummary() throws Exception {
        // lpPayload: CalPERS uc$20M · Stanford uc$10M · Tiny uc$1M excluded. Engine outputs are
        // never taken from the payload, so Agent BB is derived per LP. Total uncalled $31M →
        // agent conc cap 7.5% = $2.325M; abbM = cap × agentRate: CalPERS 2.325×0.95 = 2.20875,
        // Stanford 2.325×0.75 = 1.74375 → totalABB = 3.9525 and the shares follow. agentExcessM =
        // 20−2.325 / 10−2.325 / 0; totalUC (included) = 30; totalUEC = min(20,25)+min(10,25) = 30;
        // totalConcExcess = Tiny's 1.
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lpPayload(facilityId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'CalPERS')].ucM").value(hasItem(closeTo(20.0, 0.001))))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'CalPERS')].pctAgentBB").value(hasItem(closeTo(2.20875 / 3.9525, 0.0001))))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'Stanford Endowment')].pctAgentBB").value(hasItem(closeTo(1.74375 / 3.9525, 0.0001))))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'CalPERS')].agentExcessM").value(hasItem(closeTo(17.675, 0.001))))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'Stanford Endowment')].agentExcessM").value(hasItem(closeTo(7.675, 0.001))))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'Tiny Fund LLC')].agentExcessM").value(hasItem(closeTo(0.0, 0.001))))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'Tiny Fund LLC')].pctUbsBB").value(hasItem(closeTo(0.0, 0.0001))))
            .andExpect(jsonPath("$.result.summary.totalUEC").value(closeTo(30.0, 0.001)))
            .andExpect(jsonPath("$.result.summary.totalUC").value(closeTo(30.0, 0.001)))
            .andExpect(jsonPath("$.result.summary.totalConcExcess").value(closeTo(1.0, 0.001)))
            .andExpect(jsonPath("$.result.summary.reclassCount").value(0));
    }

    @Test
    void run_writesComputedValuesBackToLpRecords() throws Exception {
        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lpPayload(facilityId)))
            .andExpect(status().isCreated());

        var byName = new java.util.HashMap<String, com.ubs.pesubapi.entity.LpRecord>();
        lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)
            .forEach(lp -> byName.put(lp.getInvestorName(), lp));

        // Engine results are persisted onto the records in the run transaction as exact dollars �
        // these columns are NUMERIC, so the old lossy "$17.7M" rounding is gone.
        // $20M uncalled less the 7.5% agent limit on $31M total = $2.325M eligible → $17.675M excess,
        // the exact figure the old "$17.7M" string rounded away.
        assertThat(byName.get("CalPERS").getAgentExcessConcentration()).isEqualByComparingTo("17675000");
        assertThat(byName.get("CalPERS").getUbsExcessConcentration()).isEqualByComparingTo("0");
        assertThat(byName.get("CalPERS").getUbsBorrowingBase()).isNotNull();
        assertThat(byName.get("Tiny Fund LLC").getUbsExcessConcentration()).isEqualByComparingTo("1000000");
        assertThat(byName.get("Tiny Fund LLC").getAgentExcessConcentration()).isEqualByComparingTo("0");
        // abb is not part of the commit payload and the engine never writes it back — it stays
        // whatever ingest recorded (null here: these records were created by the run upsert).
        assertThat(byName.get("CalPERS").getAgentBorrowingBase()).isNull();

        // A row-less re-run recomputes from the DB and must land on the same values.
        mvc.perform(post("/api/bb/run/{id}", facilityId))
            .andExpect(status().isCreated());
        var calpers = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).getFirst();
        assertThat(calpers.getInvestorName()).isEqualTo("CalPERS");
        assertThat(calpers.getAgentExcessConcentration()).isEqualByComparingTo("17675000");
        assertThat(calpers.getAgentBorrowingBase()).isNull();
    }

    @Test
    void latestSnapshot_oldSummaryShapeStillDeserializes() throws Exception {
        // Snapshots persisted before the summary/per-row extension must keep loading: new numeric
        // fields default to 0, old fields survive. Seed the old JSON shape directly. // TEST ONLY
        String oldResult = """
            {"lps":[{"id":1,"facilityId":%d,"name":"Legacy LP","parent":null,"spv":false,"hq":true,
              "investor_type":"Institutional","inst_vs_hnw":"Institutional","region_location":"North America",
              "ig":true,"cls":"Rated Investor","sp":"AAA","mdy":"Aaa","fitch":"","aum":"$500.0B",
              "uc":"$10.0M","abb":"$9.5M","inc":true,"rcl":false,"tf":false,"rate":"90%%","agentRate":"95.0%%",
              "uec":"$10.0M","ubb":"$9.0M","delta":"-$0.5M","uecM":10.0,"ubbM":9.0,"abbM":9.5,
              "deltaM":-0.5,"concExcessM":0.0,"highQuality":true}],
             "summary":{"totalUBB":9.0,"totalABB":9.5,"bbDelta":-0.5,"ear":0.9,"agentEar":0.95,
              "earDelta":-0.05,"includedCount":1,"excludedCount":0},
             "breaches":[]}
            """.formatted(facilityId);
        jdbcTemplate.update(
            "INSERT INTO bb_snapshots (facility_id, result, calculated_at) VALUES (?, ?::jsonb, now())",
            facilityId, oldResult);

        mvc.perform(get("/api/bb/snapshots/{id}/latest", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.summary.totalUBB").value(closeTo(9.0, 0.001)))
            .andExpect(jsonPath("$.result.summary.totalUEC").value(closeTo(0.0, 0.001)))
            .andExpect(jsonPath("$.result.summary.reclassCount").value(0))
            .andExpect(jsonPath("$.result.lps[0].pctAgentBB").value(closeTo(0.0, 0.001)))
            .andExpect(jsonPath("$.result.lps[0].ubbM").value(closeTo(9.0, 0.001)));
    }

    // ── Agent BB derivation when the stored abb column is absent ─────────────────────

    @Test
    void run_derivesAgentBbFromAgentRateWhenAbbAbsent() throws Exception {
        // Records committed without an Agent BB amount (abb null) but carrying the agent rate and
        // agent concentration limit — the row-less re-run scenario. The engine must derive
        // abb = min(uc, totalUc × agentConc) × agentRate, not report totalABB = 0.
        // Two LPs, $10M uncalled each (totalUc $20M), 60% agent conc → cap $12M → eligible $10M;
        // 90% agent rate → $9M each, totalABB = $18M. The excluded LP derives to $0.
        String body = """
            {
              "lps": [
                {
                  "name": "Alpha Pension",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": true, "cls": "Rated Investor",
                  "sp": "AAA", "mdy": "Aaa", "fitch": "",
                  "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "60%", "ubsConc": "$25.0M",
                  "agentRate": 0.90,
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "name": "Beta Endowment",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": false, "cls": "Unrated NAV > $1Bn",
                  "sp": "", "mdy": "", "fitch": "",
                  "aum": "$40.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$10.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "60%", "ubsConc": "$25.0M",
                  "agentRate": 0.90,
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "name": "Gamma Excluded",
                  "parent": null, "spv": true, "hq": false,
                  "type": "HNW", "region": "Europe",
                  "ig": false, "cls": "Excluded",
                  "sp": "", "mdy": "", "fitch": "",
                  "aum": null, "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$1.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$1.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "60%", "ubsConc": "$25.0M",
                  "agentRate": 0.50,
                  "inc": false, "rcl": false, "notes": null
                }
              ]
            }
            """;

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.totalABB").value(closeTo(18.0, 0.01)))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'Alpha Pension')].abbM").value(hasItem(closeTo(9.0, 0.01))))
            .andExpect(jsonPath("$.result.lps[?(@.investorName == 'Gamma Excluded')].abbM").value(hasItem(closeTo(0.0, 0.001))));

        // The derived total must flow through to summary-ext (Agent Borrowing Base, and with it
        // Available Commitment and Current Facility Advance Rate on the Shadow BB summary).
        mvc.perform(get("/api/bb/summary-ext/{id}", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentBBRaw").value(closeTo(18.0, 0.01)))
            .andExpect(jsonPath("$.facilityAdvRate").value(closeTo(18.0 / 21.0, 0.001)));
    }

    @Test
    void run_storedZeroAbbIsNotRederived() throws Exception {
        // An explicit "$0" recorded at ingest is a real agent-reported value, not an absent one —
        // the engine must keep it rather than deriving a non-zero Agent BB from the rate columns.
        String body = """
            {
              "lps": [{
                "name": "Zeroed LP",
                "parent": null, "spv": false, "hq": true,
                "type": "Institutional", "region": "North America",
                "ig": true, "cls": "Rated Investor",
                "sp": "AAA", "mdy": "Aaa", "fitch": "",
                "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": null,
                "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                "agentConc": "60%", "ubsConc": "$25.0M",
                "agentRate": 0.90,
                "inc": true, "rcl": false, "notes": null
              }]
            }
            """;

        mvc.perform(post("/api/bb/run/{id}", facilityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        // Simulate the ingest-written agent-reported "$0" (abb is not commit-payload-settable).
        var record = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).getFirst();
        record.setAgentBorrowingBase(java.math.BigDecimal.ZERO);   // TEST ONLY
        lpRecordRepo.save(record);

        mvc.perform(post("/api/bb/run/{id}", facilityId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result.summary.totalABB").value(closeTo(0.0, 0.001)))
            .andExpect(jsonPath("$.result.lps[0].abbM").value(closeTo(0.0, 0.001)));
    }

    // ── Helper ────────────────────────────────────────────────────────────────────

    /** 3-LP payload: one Rated, one Unrated, one Excluded. TEST ONLY */
    private static String lpPayload(int facilityId) {
        return """
            {
              "lps": [
                {
                  "name": "CalPERS",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": true, "cls": "Rated Investor",
                  "sp": "AAA", "mdy": "Aaa", "fitch": "",
                  "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$20.0M", "pctCapCommit": null, "calledCap": "$14.0M",
                  "uc": "$20.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "7.5%%", "ubsConc": "$25.0M",
                  "agentRate": 0.95,
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "name": "Stanford Endowment",
                  "parent": null, "spv": false, "hq": true,
                  "type": "Institutional", "region": "North America",
                  "ig": false, "cls": "Unrated NAV > $1Bn",
                  "sp": "", "mdy": "", "fitch": "",
                  "aum": "$40.0B", "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$10.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$10.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": "7.5%%", "ubsConc": "$25.0M",
                  "agentRate": 0.75,
                  "inc": true, "rcl": false, "notes": null
                },
                {
                  "name": "Tiny Fund LLC",
                  "parent": null, "spv": true, "hq": false,
                  "type": "HNW", "region": "Europe",
                  "ig": false, "cls": "Excluded",
                  "sp": "", "mdy": "", "fitch": "",
                  "aum": null, "nav": null, "pensionAssets": null, "fundingRatio": null,
                  "capCommit": "$1.0M", "pctCapCommit": null, "calledCap": null,
                  "uc": "$1.0M", "pctUncalled": null, "pctCalled": null,
                  "agentConc": null, "ubsConc": "$25.0M",
                  "agentRate": 0,
                  "inc": false, "rcl": false, "notes": null
                }
              ]
            }
            """;
    }
}

