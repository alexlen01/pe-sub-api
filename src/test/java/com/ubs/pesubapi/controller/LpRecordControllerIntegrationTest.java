package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.repository.AuditLogRepository;

import com.ubs.pesubapi.repository.FacilityRepository;

import com.ubs.pesubapi.repository.LpRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LpRecordControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired LpRecordRepository lpRecordRepo;
    @Autowired FacilityRepository facilityRepo;
    @Autowired AuditLogRepository auditLogRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Test Fund");
        f.setAgentBank("Citibank");
        facilityId = facilityRepo.save(f).getId();
    }

    private LpRecord buildLp(String investorName, String cls) {
        LpRecord lpRecord = new LpRecord();
        lpRecord.setFacilityId(facilityId);
        lpRecord.setInvestorName(investorName);
        lpRecord.setInvType("Pension");
        lpRecord.setRegion("US");
        lpRecord.setCls(cls);
        return lpRecord;
    }

    @Test
    void listByFacility_returnsRealFieldValues() throws Exception {
        lpRecordRepo.save(buildLp("Acme Pension Fund", "Rated"));
        lpRecordRepo.save(buildLp("Beta Capital LLC", "Unrated AUM >$2bn"));

        mvc.perform(get("/api/lpRecords").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].name").value("Acme Pension Fund"))
            .andExpect(jsonPath("$[0].cls").value("Rated"))
            .andExpect(jsonPath("$[0].facilityId").value(facilityId))
            .andExpect(jsonPath("$[1].name").value("Beta Capital LLC"));
    }

    @Test
    void getById_returnsRealFieldValues() throws Exception {
        LpRecord saved = lpRecordRepo.save(buildLp("Delta Fund", "Rated"));

        mvc.perform(get("/api/lpRecords/{id}", saved.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(saved.getId()))
            .andExpect(jsonPath("$.name").value("Delta Fund"))
            .andExpect(jsonPath("$.cls").value("Rated"))
            .andExpect(jsonPath("$.facilityId").value(facilityId));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mvc.perform(get("/api/lpRecords/99999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void patchLp_updatesClsAndReturnsDto() throws Exception {
        LpRecord saved = lpRecordRepo.save(buildLp("Gamma Pension", "Rated"));

        mvc.perform(patch("/api/lpRecords/{id}", saved.getId())
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
        lpRecordRepo.save(buildLp("Included LP", "Rated"));
        lpRecordRepo.save(buildLp("Excluded LP", "Excluded"));

        mvc.perform(get("/api/lpRecords")
                .param("facilityId", String.valueOf(facilityId))
                .param("cls", "Rated"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("Included LP"));
    }

    @Test
    void listByFacilityAndSearch_filtersCorrectly() throws Exception {
        lpRecordRepo.save(buildLp("Apollo Capital", "Rated"));
        lpRecordRepo.save(buildLp("Beta Partners", "Rated"));

        mvc.perform(get("/api/lpRecords")
                .param("facilityId", String.valueOf(facilityId))
                .param("search", "Apollo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("Apollo Capital"));
    }

    @Test
    void listLps_nullDataFields_notHardcodedStrings() throws Exception {
        LpRecord lpRecord = buildLp("Sparse LP", "Rated");
        lpRecordRepo.save(lpRecord);

        mvc.perform(get("/api/lpRecords").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].aum").doesNotExist())
            .andExpect(jsonPath("$[0].uc").doesNotExist())
            .andExpect(jsonPath("$[0].capCommit").doesNotExist());
    }

    // ── Batch classification save (Shadow BB "Save") ────────────────────────────────

    @Test
    void patchClassification_updatesLpRecordAndUpsertsRate() throws Exception {
        LpRecord saved = lpRecordRepo.save(buildLp("Monarch Capital LP", "Eligible"));

        mvc.perform(patch("/api/lpRecords/classification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "facilityId": %d,
                      "effectiveDate": "2026-06",
                      "rows": [{
                        "name": "Monarch Capital LP",
                        "cls": "Rated", "sp": "AA", "mdy": "Aa2", "fitch": "AA",
                        "aum": "$4.2B", "nav": "$3.1B", "pension": "$1.0B", "pensionFunded": "112%%",
                        "inc": true, "uc": "$12.0M",
                        "ubsAdvRatePct": 90.0, "ubsConcLimitPct": 7.5
                      }]
                    }
                    """.formatted(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(1));

        // LP entity fields updated in place — including the Financial Scale columns
        mvc.perform(get("/api/lpRecords/{id}", saved.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cls").value("Rated"))
            .andExpect(jsonPath("$.sp").value("AA"))
            .andExpect(jsonPath("$.aum").value("$4.2B"))
            .andExpect(jsonPath("$.nav").value("$3.1B"))
            .andExpect(jsonPath("$.pension").value("$1.0B"))
            .andExpect(jsonPath("$.pensionFunded").value("112%"))
            .andExpect(jsonPath("$.uc").value("$12.0M"))
            .andExpect(jsonPath("$.inc").value(true));

        // Advance rate + conc limit upserted into lp_rates as decimal fractions
        mvc.perform(get("/api/lpRecords/rates").param("effective_date", "2026-06"))
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
        lpRecordRepo.save(buildLp("Solstice Capital LP", "Eligible"));

        mvc.perform(patch("/api/lpRecords/classification")
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
        mvc.perform(get("/api/lpRecords/rates").param("effective_date", "2026-06"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].lpName").value("Solstice Capital LP"))
            .andExpect(jsonPath("$[0].effectiveDate").value("2026-06-01"));
    }

    @Test
    void patchClassification_unmatchedNameIgnored_returnsZero() throws Exception {
        lpRecordRepo.save(buildLp("Real LP", "Eligible"));

        mvc.perform(patch("/api/lpRecords/classification")
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
        lpRecordRepo.save(buildLp("Monarch Capital LP", "Eligible"));

        mvc.perform(patch("/api/lpRecords/classification")
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
        lpRecordRepo.save(buildLp("Monarch Capital LP", "Eligible"));
        lpRecordRepo.save(buildLp("Solstice Capital LP", "Eligible"));

        mvc.perform(patch("/api/lpRecords/classification")
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

    // ── Same-name lines: an LP may appear on multiple lines within one facility ──────
    // Distinct fund sleeves / vintages, or two Agent BB lines both accepted against one LP Master
    // entry. Collapsing them would drop a commitment line and understate the borrowing base, so
    // both persist as distinct records (surrogate-PK identity). See migration V1_4.

    @Test
    void duplicateInvestorNameInFacility_keepsBothLines() {
        lpRecordRepo.saveAndFlush(buildLp("Acme Pension Fund", "Rated"));
        lpRecordRepo.saveAndFlush(buildLp("Acme Pension Fund", "Eligible"));

        assertThat(lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)).hasSize(2);
    }
}
