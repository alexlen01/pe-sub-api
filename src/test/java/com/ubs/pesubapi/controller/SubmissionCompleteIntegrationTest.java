package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRateRepository;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubmissionCompleteIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc                        mvc;
    @Autowired SubmissionRepository           submissionRepo;
    @Autowired FacilityRepository             facilityRepo;
    @Autowired AuditLogRepository             auditLogRepo;
    @Autowired MatchQueueEntryRepository      matchQueueRepo;
    @Autowired SubmissionExtractionRepository extractionRepo;
    @Autowired LpRateRepository               rateRepo;
    @Autowired LpRepository                   lpRepo;

    private int facilityId;
    private int submissionId;

    @BeforeEach
    void setup() {
        matchQueueRepo.deleteAll();
        extractionRepo.deleteAll();
        submissionRepo.deleteAll();
        rateRepo.deleteAll();
        lpRepo.deleteAll();
        auditLogRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("Apex Growth Fund III");
        f.setAgentBank("Goldman Sachs");
        facilityId = facilityRepo.save(f).getId();

        Submission s = new Submission();
        s.setFacilityId(facilityId);
        s.setAgentBank("Goldman Sachs");
        s.setPeriodMonth("2026-05");
        s.setFileName("bb_may26.pdf");
        s.setStatus("Review");
        s.setWizardStep(5);
        submissionId = submissionRepo.save(s).getId();
    }

    @Test
    void complete_setsSubmissionProcessedAndFacilityActive() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Processed"))
            .andExpect(jsonPath("$.wizardStep").value(6))
            .andExpect(jsonPath("$.facilityName").value("Apex Growth Fund III"));

        // Facility status must be Active in the database
        Facility f = facilityRepo.findById(facilityId).orElseThrow();
        assert "Active".equals(f.getStatus()) : "Expected Active, got: " + f.getStatus();
        assert f.getLastRunAt() != null : "lastRunAt must be stamped";
    }

    @Test
    void complete_isIdempotent() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId))
            .andExpect(status().isOk());

        // Second call must also succeed and return same state
        mvc.perform(post("/api/submissions/{id}/complete", submissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Processed"))
            .andExpect(jsonPath("$.wizardStep").value(6));
    }

    @Test
    void complete_returns404ForUnknownSubmission() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", 999999))
            .andExpect(status().isNotFound());
    }

    @Test
    void complete_responseContainsFacilityId() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.facilityId").value(facilityId))
            .andExpect(jsonPath("$.agentBank").value("Goldman Sachs"))
            .andExpect(jsonPath("$.periodMonth").value("2026-05"));
    }
}
