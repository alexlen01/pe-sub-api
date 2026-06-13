package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.entity.LpRate;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRateRepository;
import com.ubs.pesubapi.repository.LpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SuppressWarnings("null")
class LpRateControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc             mvc;
    @Autowired LpRateRepository    rateRepo;
    @Autowired LpRepository        lpRepo;
    @Autowired AuditLogRepository  auditLogRepo;
    @Autowired FacilityRepository  facilityRepo;

    private int facilityId;
    private int lpId;

    @BeforeEach
    void setup() {
        rateRepo.deleteAll();
        lpRepo.deleteAll();
        auditLogRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("Test Fund");
        f.setAgentBank("Citibank");
        facilityId = facilityRepo.save(f).getId();

        Lp lp = new Lp();
        lp.setFacilityId(facilityId);
        lp.setInvestorName("Monarch Alternative Capital LP");
        lp.setInvType("Institutional");
        lp.setRegion("North America");
        lp.setCls("Rated");
        lpId = lpRepo.save(lp).getId();
    }

    @Test
    void getRate_returnsEmptyWhenNoRates() throws Exception {
        mvc.perform(get("/api/lps/rates").param("effective_date", "2026-05"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getRate_returnsMostRecentRateAsOf() throws Exception {
        LpRate old = buildRate(lpId, "2026-03-01", "Rated", 0.90, 0.075);
        LpRate cur = buildRate(lpId, "2026-05-01", "Rated", 0.90, 0.075);
        rateRepo.save(old);
        rateRepo.save(cur);

        mvc.perform(get("/api/lps/rates").param("effective_date", "2026-05"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].lpName").value("Monarch Alternative Capital LP"))
            .andExpect(jsonPath("$[0].classification").value("Rated"))
            .andExpect(jsonPath("$[0].ubsAdvRatePct").value(0.9))
            .andExpect(jsonPath("$[0].ubsConcLimitPct").value(0.075))
            .andExpect(jsonPath("$[0].effectiveDate").value("2026-05-01"));
    }

    @Test
    void getRate_doesNotReturnFutureRates() throws Exception {
        LpRate future = buildRate(lpId, "2026-07-01", "Rated", 0.90, 0.075);
        rateRepo.save(future);

        mvc.perform(get("/api/lps/rates").param("effective_date", "2026-05"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void batchUpsert_insertsRatesAndReturnsCount() throws Exception {
        String body = """
            {
              "effectiveDate": "2026-05",
              "rates": [
                {
                  "lpName": "Monarch Alternative Capital LP",
                  "classification": "Rated",
                  "ubsAdvRatePct": 0.90,
                  "ubsConcLimitPct": 0.075,
                  "source": "BATCH_FEED"
                }
              ]
            }
            """;

        mvc.perform(post("/api/lps/rates/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.saved").value(1));

        mvc.perform(get("/api/lps/rates").param("effective_date", "2026-05"))
            .andExpect(jsonPath("$[0].ubsAdvRatePct").value(0.9))
            .andExpect(jsonPath("$[0].ubsConcLimitPct").value(0.075));
    }

    @Test
    void batchUpsert_isIdempotent() throws Exception {
        String body = """
            {
              "effectiveDate": "2026-05",
              "rates": [
                {
                  "lpName": "Monarch Alternative Capital LP",
                  "classification": "Unrated >2bn",
                  "ubsAdvRatePct": 0.75,
                  "ubsConcLimitPct": 0.05,
                  "source": "BATCH_FEED"
                }
              ]
            }
            """;

        mvc.perform(post("/api/lps/rates/batch")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/lps/rates/batch")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.saved").value(1));

        mvc.perform(get("/api/lps/rates").param("effective_date", "2026-05"))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].ubsAdvRatePct").value(0.75));
    }

    @Test
    void batchUpsert_returns422ForUnknownLpName() throws Exception {
        String body = """
            {
              "effectiveDate": "2026-05",
              "rates": [
                {
                  "lpName": "Nonexistent Fund LLC",
                  "classification": "Rated",
                  "ubsAdvRatePct": 0.90,
                  "ubsConcLimitPct": 0.075
                }
              ]
            }
            """;

        mvc.perform(post("/api/lps/rates/batch")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getRate_returns400ForBadDateFormat() throws Exception {
        mvc.perform(get("/api/lps/rates").param("effective_date", "not-a-date"))
            .andExpect(status().isBadRequest());
    }

    private LpRate buildRate(int lpId, String date, String cls, double adv, double conc) {
        LpRate r = new LpRate();
        r.setLpId(lpId);
        r.setEffectiveDate(LocalDate.parse(date));
        r.setClassification(cls);
        r.setUbsAdvRatePct(BigDecimal.valueOf(adv));
        r.setUbsConcLimitPct(BigDecimal.valueOf(conc));
        return r;
    }
}
