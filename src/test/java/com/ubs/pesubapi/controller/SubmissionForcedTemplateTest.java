package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.entity.SubmissionExtraction;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The operator-forced Agent BB template (V1_5 migration) must round-trip through the real
 * Postgres schema, and the re-extract endpoint must accept an optional template override body.
 */
class SubmissionForcedTemplateTest extends IntegrationTestBase {

    @Autowired MockMvc                        mvc;
    @Autowired SubmissionExtractionRepository extractionRepo;
    @Autowired SubmissionRepository           submissionRepo;
    @Autowired FacilityRepository             facilityRepo;
    @Autowired AuditLogRepository             auditLogRepo;

    private int submissionId;

    @BeforeEach
    void setup() {
        extractionRepo.deleteAll();
        submissionRepo.deleteAll();
        auditLogRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("Audax Direct Lending VII");
        f.setAgentBank("Audax");
        int facilityId = facilityRepo.save(f).getId();

        Submission s = new Submission();
        s.setFacilityId(facilityId);
        s.setAgentBank("Audax");
        s.setPeriodMonth("2026-05");
        s.setFileName("Agent-BB-Audax-Fund-VII.xlsx");
        s.setStatus("Review");
        submissionId = submissionRepo.save(s).getId();
    }

    @Test
    void forcedTemplate_roundTripsThroughPostgres() {
        SubmissionExtraction e = new SubmissionExtraction();
        e.setSubmissionId(submissionId);
        e.setForcedTemplate("Audax Fund VII");
        e.setTotalRows(0);
        e.setFlaggedCount(0);
        extractionRepo.save(e);

        SubmissionExtraction reloaded = extractionRepo.findBySubmissionId(submissionId).orElseThrow();
        assertThat(reloaded.getForcedTemplate()).isEqualTo("Audax Fund VII");
    }

    @Test
    void forcedTemplate_nullByDefault_meansAutoDetect() {
        SubmissionExtraction e = new SubmissionExtraction();
        e.setSubmissionId(submissionId);
        e.setTotalRows(0);
        e.setFlaggedCount(0);
        extractionRepo.save(e);

        SubmissionExtraction reloaded = extractionRepo.findBySubmissionId(submissionId).orElseThrow();
        assertThat(reloaded.getForcedTemplate()).isNull();
    }

    @Test
    void reextract_acceptsTemplateOverrideBody_butReturns404ForUnknownSubmission() throws Exception {
        // Unknown submission short-circuits before reaching the (test-unreachable) extraction
        // service, proving the new optional @RequestBody parses without error.
        mvc.perform(post("/api/submissions/{id}/reextract", 999999)
                .contentType("application/json")
                .content("{\"templateName\":\"Audax Fund VII\"}"))
            .andExpect(status().isNotFound());
    }
}
