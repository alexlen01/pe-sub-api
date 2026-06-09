package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SuppressWarnings("null")
class LpControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired LpRepository lpRepo;
    @Autowired FacilityRepository facilityRepo;
    @Autowired AuditLogRepository auditLogRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        lpRepo.deleteAll();
        auditLogRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("Test Fund");
        f.setAgentBank("Citibank");
        facilityId = facilityRepo.save(f).getId();
    }

    private Lp buildLp(String name, String cls, int rank) {
        Lp lp = new Lp();
        lp.setFacilityId(facilityId);
        lp.setRank(rank);
        lp.setName(name);
        lp.setType("Pension");
        lp.setRegion("US");
        lp.setCls(cls);
        return lp;
    }

    @Test
    void listByFacility_returnsRealFieldValues() throws Exception {
        lpRepo.save(buildLp("Acme Pension Fund", "Rated", 1));
        lpRepo.save(buildLp("Beta Capital LLC", "Unrated AUM >$2bn", 2));

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
        Lp saved = lpRepo.save(buildLp("Delta Fund", "Rated", 1));

        mvc.perform(get("/api/lps/{id}", saved.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(saved.getId()))
            .andExpect(jsonPath("$.name").value("Delta Fund"))
            .andExpect(jsonPath("$.cls").value("Rated"))
            .andExpect(jsonPath("$.facilityId").value(facilityId))
            .andExpect(jsonPath("$.rank").value(1));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mvc.perform(get("/api/lps/99999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void patchLp_updatesClsAndReturnsDto() throws Exception {
        Lp saved = lpRepo.save(buildLp("Gamma Pension", "Rated", 1));

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
        lpRepo.save(buildLp("Included LP", "Rated", 1));
        lpRepo.save(buildLp("Excluded LP", "Excluded", 2));

        mvc.perform(get("/api/lps")
                .param("facilityId", String.valueOf(facilityId))
                .param("cls", "Rated"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("Included LP"));
    }

    @Test
    void listByFacilityAndSearch_filtersCorrectly() throws Exception {
        lpRepo.save(buildLp("Apollo Capital", "Rated", 1));
        lpRepo.save(buildLp("Beta Partners", "Rated", 2));

        mvc.perform(get("/api/lps")
                .param("facilityId", String.valueOf(facilityId))
                .param("search", "Apollo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("Apollo Capital"));
    }

    @Test
    void listLps_nullDataFields_notHardcodedStrings() throws Exception {
        Lp lp = buildLp("Sparse LP", "Rated", 1);
        lpRepo.save(lp);

        mvc.perform(get("/api/lps").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].aum").doesNotExist())
            .andExpect(jsonPath("$[0].uc").doesNotExist())
            .andExpect(jsonPath("$[0].capCommit").doesNotExist());
    }
}
