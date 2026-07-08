package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.repository.AuditLogRepository;

import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FacilityControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc             mvc;
    @Autowired FacilityRepository  facilityRepo;
    @Autowired LpRecordRepository        lpRecordRepo;
    @Autowired AuditLogRepository  auditLogRepo;

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
    void listAndGet_reportLpCountFromLpRecords() throws Exception {
        com.ubs.pesubapi.entity.Facility f = new com.ubs.pesubapi.entity.Facility();
        f.setName("Counted Fund");          // TEST ONLY
        f.setAgentBank("Wells Fargo");
        int id = facilityRepo.save(f).getId();

        lpRecordRepo.save(buildLp(id, "Acme Pension Fund"));
        lpRecordRepo.save(buildLp(id, "Beta Capital LLC"));

        // Empty facility — lpCount must be 0, never null/absent
        com.ubs.pesubapi.entity.Facility empty = new com.ubs.pesubapi.entity.Facility();
        empty.setName("Empty Fund");        // TEST ONLY
        empty.setAgentBank("Citibank");
        facilityRepo.save(empty);

        mvc.perform(get("/api/facilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name=='Counted Fund')].lpCount", contains(2)))
            .andExpect(jsonPath("$[?(@.name=='Empty Fund')].lpCount", contains(0)));

        mvc.perform(get("/api/facilities/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lpCount").value(2));
    }

    private com.ubs.pesubapi.entity.LpRecord buildLp(int facilityId, String investorName) {
        com.ubs.pesubapi.entity.LpRecord lpRecord = new com.ubs.pesubapi.entity.LpRecord();
        lpRecord.setFacilityId(facilityId);
        lpRecord.setInvestorName(investorName);
        lpRecord.setInvType("Institutional");
        lpRecord.setRegion("US");
        lpRecord.setCls("Eligible");
        return lpRecord;
    }

    @Test
    void createFacility_dtoShapeHasNullMetadataAndNoEntityLeak() throws Exception {
        // account_number, loan_amount, maturity_date, collateral_date, bank_status, bank_status_date are
        // schema columns (V1_1) the create endpoint does not set. Verify they are present
        // in the DTO response as JSON null values (proving the fields round-trip the API),
        // and that the list endpoint returns the DTO shape (concLimitM etc., not a JPA proxy).
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Metadata Round-Trip Fund", "agentBank": "JPMorgan"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accountNumber").value((Object) null))
            .andExpect(jsonPath("$.loanAmount").value((Object) null))
            .andExpect(jsonPath("$.maturityDate").value((Object) null))
            .andExpect(jsonPath("$.collateralDate").value((Object) null))
            .andExpect(jsonPath("$.bankStatus").value((Object) null))
            .andExpect(jsonPath("$.bankStatusDate").value((Object) null));

        int id = facilityRepo.findByName("Metadata Round-Trip Fund").orElseThrow().getId();

        mvc.perform(get("/api/facilities/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value((Object) null))
            .andExpect(jsonPath("$.loanAmount").value((Object) null))
            .andExpect(jsonPath("$.maturityDate").value((Object) null))
            .andExpect(jsonPath("$.collateralDate").value((Object) null))
            .andExpect(jsonPath("$.bankStatus").value((Object) null))
            .andExpect(jsonPath("$.bankStatusDate").value((Object) null));

        mvc.perform(get("/api/facilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].concLimitM").value(25))
            .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
            .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
    }

    @Test
    void patchFacility_updatesSummaryFields_andRoundTrips() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Editable Fund", "agentBank": "JPMorgan"}
                    """))
            .andExpect(status().isCreated());

        int id = facilityRepo.findByName("Editable Fund").orElseThrow().getId();

        // PATCH the Agent Bank Summary inputs plus facility_size / ubs_participation
        // (the Shadow BB Borrowing Base summary inputs) and assert the response reflects them.
        mvc.perform(patch("/api/facilities/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountNumber": "5VX1796", "loanAmount": 2500000000.00, "maturityDate": "2029-03-15", "collateralDate": "2026-06-01",
                     "facilitySize": 2000000000.00, "ubsParticipation": 500000000.00}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("5VX1796"))
            .andExpect(jsonPath("$.loanAmount").value(2500000000.00))
            .andExpect(jsonPath("$.maturityDate").value("2029-03-15"))
            .andExpect(jsonPath("$.collateralDate").value("2026-06-01"))
            .andExpect(jsonPath("$.facilitySize").value(2000000000.00))
            .andExpect(jsonPath("$.ubsParticipation").value(500000000.00));

        // GET confirms the values persisted (POST → PATCH → GET round-trip).
        mvc.perform(get("/api/facilities/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("5VX1796"))
            .andExpect(jsonPath("$.loanAmount").value(2500000000.00))
            .andExpect(jsonPath("$.maturityDate").value("2029-03-15"))
            .andExpect(jsonPath("$.collateralDate").value("2026-06-01"))
            .andExpect(jsonPath("$.facilitySize").value(2000000000.00))
            .andExpect(jsonPath("$.ubsParticipation").value(500000000.00));
    }

    @Test
    void patchFacility_partialUpdate_leavesUnsetFieldsUnchanged() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Partial Fund", "agentBank": "Citibank"}
                    """))
            .andExpect(status().isCreated());

        int id = facilityRepo.findByName("Partial Fund").orElseThrow().getId();

        mvc.perform(patch("/api/facilities/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountNumber": "5VA0001"}
                    """))
            .andExpect(status().isOk());

        // A second PATCH with only loanAmount must not clear the previously-set accountNumber.
        mvc.perform(patch("/api/facilities/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"loanAmount": 100000000.00}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("5VA0001"))
            .andExpect(jsonPath("$.loanAmount").value(100000000.00));
    }

    @Test
    void patchFacility_notFound_returns404() throws Exception {
        mvc.perform(patch("/api/facilities/99999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountNumber": "5VX0000"}
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void patchFacility_renamesNameAndAgentBank_andRoundTrips() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Old Name Fund", "agentBank": "Citibank"}
                    """))
            .andExpect(status().isCreated());

        int id = facilityRepo.findByName("Old Name Fund").orElseThrow().getId();

        mvc.perform(patch("/api/facilities/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "New Name Fund", "agentBank": "JPMorgan"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("New Name Fund"))
            .andExpect(jsonPath("$.agentBank").value("JPMorgan"));

        mvc.perform(get("/api/facilities/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("New Name Fund"))
            .andExpect(jsonPath("$.agentBank").value("JPMorgan"));
    }

    @Test
    void patchFacility_renameToExistingName_returns409() throws Exception {
        for (String name : new String[] { "First Fund", "Second Fund" }) {
            mvc.perform(post("/api/facilities")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"" + name + "\", \"agentBank\": \"Citibank\"}"))
                .andExpect(status().isCreated());
        }

        int secondId = facilityRepo.findByName("Second Fund").orElseThrow().getId();

        mvc.perform(patch("/api/facilities/{id}", secondId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "First Fund"}
                    """))
            .andExpect(status().isConflict());

        // Renaming a facility to its own (unchanged) name must be allowed.
        mvc.perform(patch("/api/facilities/{id}", secondId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Second Fund"}
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void deactivate_succeedsWhenFacilityHasNoLpRecords() throws Exception {
        com.ubs.pesubapi.entity.Facility f = new com.ubs.pesubapi.entity.Facility();
        f.setName("Deactivatable Fund");    // TEST ONLY
        f.setAgentBank("Citibank");
        int id = facilityRepo.save(f).getId();

        mvc.perform(patch("/api/facilities/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status": "Inactive"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Inactive"));
    }

    @Test
    void deactivate_blockedWhenFacilityHasLpRecords_returns409() throws Exception {
        com.ubs.pesubapi.entity.Facility f = new com.ubs.pesubapi.entity.Facility();
        f.setName("Populated Fund");        // TEST ONLY
        f.setAgentBank("Citibank");
        int id = facilityRepo.save(f).getId();
        lpRecordRepo.save(buildLp(id, "Acme Pension Fund"));

        mvc.perform(patch("/api/facilities/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status": "Inactive"}
                    """))
            .andExpect(status().isConflict());
    }

    @Test
    void deleteFacility_withNoLpRecords_removesFacility() throws Exception {
        com.ubs.pesubapi.entity.Facility f = new com.ubs.pesubapi.entity.Facility();
        f.setName("Deletable Fund");        // TEST ONLY
        f.setAgentBank("Citibank");
        int id = facilityRepo.save(f).getId();

        mvc.perform(delete("/api/facilities/{id}", id))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/facilities/{id}", id))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteFacility_preservesAuditHistory() throws Exception {
        com.ubs.pesubapi.entity.Facility f = new com.ubs.pesubapi.entity.Facility();
        f.setName("Audited Fund");          // TEST ONLY
        f.setAgentBank("Citibank");
        int id = facilityRepo.save(f).getId();

        com.ubs.pesubapi.entity.AuditLog entry = new com.ubs.pesubapi.entity.AuditLog();
        entry.setEvent("Facility Created");
        entry.setDetail("Audited Fund");
        entry.setFacilityId(id);
        int auditId = auditLogRepo.save(entry).getId();

        mvc.perform(delete("/api/facilities/{id}", id))
            .andExpect(status().isNoContent());

        // The audit row survives the deletion; only its facility reference is cleared.
        com.ubs.pesubapi.entity.AuditLog reloaded = auditLogRepo.findById(auditId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(reloaded.getFacilityId());
    }

    @Test
    void deleteFacility_withLpRecords_returns409_andKeepsFacility() throws Exception {
        com.ubs.pesubapi.entity.Facility f = new com.ubs.pesubapi.entity.Facility();
        f.setName("Undeletable Fund");      // TEST ONLY
        f.setAgentBank("Citibank");
        int id = facilityRepo.save(f).getId();
        lpRecordRepo.save(buildLp(id, "Acme Pension Fund"));

        mvc.perform(delete("/api/facilities/{id}", id))
            .andExpect(status().isConflict());

        mvc.perform(get("/api/facilities/{id}", id))
            .andExpect(status().isOk());
    }

    @Test
    void deleteFacility_notFound_returns404() throws Exception {
        mvc.perform(delete("/api/facilities/99999"))
            .andExpect(status().isNotFound());
    }
}
