package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
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
class FacilityControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc             mvc;
    @Autowired FacilityRepository  facilityRepo;
    @Autowired LpRepository        lpRepo;
    @Autowired BbSnapshotRepository snapshotRepo;
    @Autowired AuditLogRepository  auditLogRepo;

    @BeforeEach
    void clean() {
        // Delete dependents before facilities to satisfy FK constraints.
        // Order: audit_log → bb_snapshots → lps → facilities
        auditLogRepo.deleteAll();
        snapshotRepo.deleteAll();
        lpRepo.deleteAll();
        facilityRepo.deleteAll();
    }

    @Test
    void createAndListFacility() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Test Fund I", "agentBank": "Citibank"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Test Fund I"))
            .andExpect(jsonPath("$.agentBank").value("Citibank"))
            .andExpect(jsonPath("$.status").value("Not Started"))
            .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(get("/api/facilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("Test Fund I"))
            .andExpect(jsonPath("$[0].agentBank").value("Citibank"));
    }

    @Test
    void getFacilityById() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Fund Alpha", "agentBank": "JPMorgan"}
                    """))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/facilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Fund Alpha"));

        int id = facilityRepo.findByName("Fund Alpha").orElseThrow().getId();

        mvc.perform(get("/api/facilities/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.name").value("Fund Alpha"))
            .andExpect(jsonPath("$.agentBank").value("JPMorgan"))
            .andExpect(jsonPath("$.status").value("Not Started"));
    }

    @Test
    void getFacilityById_notFound_returns404() throws Exception {
        mvc.perform(get("/api/facilities/99999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void patchStatus_updatesAndReturnsDto() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Fund Beta", "agentBank": "Goldman"}
                    """))
            .andExpect(status().isCreated());

        int id = facilityRepo.findByName("Fund Beta").orElseThrow().getId();

        mvc.perform(patch("/api/facilities/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status": "Active"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Active"))
            .andExpect(jsonPath("$.name").value("Fund Beta"));
    }

    @Test
    void createDuplicateFacility_returns409() throws Exception {
        String body = """
            {"name": "Unique Fund", "agentBank": "Wells Fargo"}
            """;
        mvc.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/facilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
    }

    @Test
    void createFacility_missingName_returns400() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"agentBank": "Citibank"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listFacilities_returnsNoDatabaseEntityFields() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "No Entity Fund", "agentBank": "Barclays"}
                    """))
            .andExpect(status().isCreated());

        // Verify response is DTO shape (has concLimitM, not a JPA proxy)
        mvc.perform(get("/api/facilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].concLimitM").value(25))
            .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
            .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
    }
}
