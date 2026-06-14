package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRateRepository;
import com.ubs.pesubapi.repository.LpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SuppressWarnings("null")
class LpControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired LpRepository lpRepo;
    @Autowired LpRateRepository rateRepo;
    @Autowired FacilityRepository facilityRepo;
    @Autowired AuditLogRepository auditLogRepo;
    @Autowired BbSnapshotRepository snapshotRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        snapshotRepo.deleteAll();
        rateRepo.deleteAll();
        lpRepo.deleteAll();
        auditLogRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("Test Fund");
        f.setAgentBank("Citibank");
        facilityId = facilityRepo.save(f).getId();
    }

    private Lp buildLp(String investorName, String cls) {
        Lp lp = new Lp();
        lp.setFacilityId(facilityId);
        lp.setInvestorName(investorName);
        lp.setInvType("Pension");
        lp.setRegion("US");
        lp.setCls(cls);
        return lp;
    }

    @Test
    void listByFacility_returnsRealFieldValues() throws Exception {
        lpRepo.save(buildLp("Acme Pension Fund", "Rated"));
        lpRepo.save(buildLp("Beta Capital LLC", "Unrated AUM >$2bn"));

        mvc.perform(get("/api/lps").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].name").value("Acme Pension Fund"))
            .andExpect(jsonPath("$[0].cls").value("Rated"))
            .andExpect(jsonPath("$[0].facilityId").value(facilityId))
            .andExpect(jsonPath("$[1].name").value("Beta Capital LLC"));
    }

    @Test
    void getById_returnsRealFieldValues() throws Exception {
        Lp saved = lpRepo.save(buildLp("Delta Fund", "Rated"));

        mvc.perform(get("/api/lps/{id}", saved.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(saved.getId()))
            .andExpect(jsonPath("$.name").value("Delta Fund"))
            .andExpect(jsonPath("$.cls").value("Rated"))
            .andExpect(jsonPath("$.facilityId").value(facilityId));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mvc.perform(get("/api/lps/99999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void patchLp_updatesClsAndReturnsDto() throws Exception {
        Lp saved = lpRepo.save(buildLp("Gamma Pension", "Rated"));

        mvc.perform(patch("/api/lps/{id}", saved.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"cls": "Excluded", "notes": "Manually excluded"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cls").value("Excluded"))
            .andExpect(jsonPath("$.notes").value("Manually excluded"))
            .andExpect(jsonPath("$.name").value("Gamma Pension"));
    }

    @Test
    void listByFacilityAndCls_filtersCorrectly() throws Exception {
        lpRepo.save(buildLp("Included LP", "Rated"));
        lpRepo.save(buildLp("Excluded LP", "Excluded"));

        mvc.perform(get("/api/lps")
                .param("facilityId", String.valueOf(facilityId))
                .param("cls", "Rated"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("Included LP"));
    }

    @Test
    void listByFacilityAndSearch_filtersCorrectly() throws Exception {
        lpRepo.save(buildLp("Apollo Capital", "Rated"));
        lpRepo.save(buildLp("Beta Partners", "Rated"));

        mvc.perform(get("/api/lps")
                .param("facilityId", String.valueOf(facilityId))
                .param("search", "Apollo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("Apollo Capital"));
    }

    @Test
    void listLps_nullDataFields_notHardcodedStrings() throws Exception {
        Lp lp = buildLp("Sparse LP", "Rated");
        lpRepo.save(lp);

        mvc.perform(get("/api/lps").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].aum").doesNotExist())
            .andExpect(jsonPath("$[0].uc").doesNotExist())
            .andExpect(jsonPath("$[0].capCommit").doesNotExist());
    }

    // ── Batch classification save (Shadow BB "Save") ────────────────────────────────

    @Test
    void patchClassification_updatesLpRecordAndUpsertsRate() throws Exception {
        Lp saved = lpRepo.save(buildLp("Monarch Capital LP", "Eligible"));

        mvc.perform(patch("/api/lps/classification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "facilityId": %d,
                      "effectiveDate": "2026-06",
                      "rows": [{
                        "name": "Monarch Capital LP",
                        "cls": "Rated", "sp": "AA", "mdy": "Aa2", "fitch": "AA",
                        "inc": true, "uc": "$12.0M",
                        "ubsAdvRatePct": 90.0, "ubsConcLimitPct": 7.5
                      }]
                    }
                    """.formatted(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(1));

        // LP entity fields updated in place
        mvc.perform(get("/api/lps/{id}", saved.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cls").value("Rated"))
            .andExpect(jsonPath("$.sp").value("AA"))
            .andExpect(jsonPath("$.uc").value("$12.0M"))
            .andExpect(jsonPath("$.inc").value(true));

        // Advance rate + conc limit upserted into lp_rates as decimal fractions
        mvc.perform(get("/api/lps/rates").param("effective_date", "2026-06"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].lpName").value("Monarch Capital LP"))
            .andExpect(jsonPath("$[0].ubsAdvRatePct").value(closeTo(0.9, 0.0001)))
            .andExpect(jsonPath("$[0].ubsConcLimitPct").value(closeTo(0.075, 0.0001)));
    }

    @Test
    void patchClassification_acceptsFullDateEffectiveDate() throws Exception {
        // The Upload screen's date picker stores periodMonth as YYYY-MM-DD, which the Save
        // action forwards verbatim as effectiveDate. It must normalise to the first of the month
        // (not 500 on YearMonth.parse) and key the upserted rate by that month.
        lpRepo.save(buildLp("Solstice Capital LP", "Eligible"));

        mvc.perform(patch("/api/lps/classification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "facilityId": %d,
                      "effectiveDate": "2026-06-14",
                      "rows": [{
                        "name": "Solstice Capital LP",
                        "cls": "Rated", "ubsAdvRatePct": 90.0, "ubsConcLimitPct": 7.5
                      }]
                    }
                    """.formatted(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(1));

        // Rate upserted against the month (2026-06-01), retrievable as-of that month
        mvc.perform(get("/api/lps/rates").param("effective_date", "2026-06"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].lpName").value("Solstice Capital LP"))
            .andExpect(jsonPath("$[0].effectiveDate").value("2026-06-01"));
    }

    @Test
    void patchClassification_unmatchedNameIgnored_returnsZero() throws Exception {
        lpRepo.save(buildLp("Real LP", "Eligible"));

        mvc.perform(patch("/api/lps/classification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "facilityId": %d,
                      "rows": [{ "name": "Ghost LP That Does Not Exist", "cls": "Rated" }]
                    }
                    """.formatted(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(0));
    }

    @Test
    void patchClassification_withoutAuditFlag_writesNoAuditEntry() throws Exception {
        // Per-row auto-save (audit omitted/false) persists data but must NOT log — otherwise the
        // audit trail gets one entry per keystroke.
        lpRepo.save(buildLp("Monarch Capital LP", "Eligible"));

        mvc.perform(patch("/api/lps/classification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "facilityId": %d,
                      "rows": [{ "name": "Monarch Capital LP", "cls": "Rated" }]
                    }
                    """.formatted(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(1));

        assertThat(auditLogRepo.count()).isZero();
    }

    @Test
    void patchClassification_withAuditFlag_writesOneAggregatedEntry() throws Exception {
        // The flush sent when the user leaves the screen carries audit:true and the full set of
        // edited rows, producing exactly one entry recording the aggregate count.
        lpRepo.save(buildLp("Monarch Capital LP", "Eligible"));
        lpRepo.save(buildLp("Solstice Capital LP", "Eligible"));

        mvc.perform(patch("/api/lps/classification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "facilityId": %d,
                      "audit": true,
                      "rows": [
                        { "name": "Monarch Capital LP",  "cls": "Rated" },
                        { "name": "Solstice Capital LP", "cls": "Rated" }
                      ]
                    }
                    """.formatted(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(2));

        assertThat(auditLogRepo.count()).isEqualTo(1);
        assertThat(auditLogRepo.findAll().getFirst().getDetail())
            .contains("2 LP records updated from Shadow BB classification");
    }

    // ── Dedup: one record per (facility, investor name) ─────────────────────────────

    @Test
    void duplicateInvestorNameInFacility_violatesUniqueConstraint() {
        lpRepo.saveAndFlush(buildLp("Acme Pension Fund", "Rated"));

        assertThatThrownBy(() ->
            lpRepo.saveAndFlush(buildLp("Acme Pension Fund", "Eligible")))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)).hasSize(1);
    }
}
