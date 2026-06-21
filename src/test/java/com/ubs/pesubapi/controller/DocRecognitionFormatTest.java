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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the Document-Recognition "Format" string: the structurally-recognised fund
 * template (template_version) is preferred, falling back to the agent-bank template format,
 * then to "Unknown template".
 */
class DocRecognitionFormatTest extends IntegrationTestBase {

    @Autowired MockMvc                        mvc;
    @Autowired FacilityRepository             facilityRepo;
    @Autowired SubmissionRepository           submissionRepo;
    @Autowired SubmissionExtractionRepository extractionRepo;
    @Autowired AuditLogRepository             auditLogRepo;

    private int facilityId;

    @BeforeEach
    void setup() {
        extractionRepo.deleteAll();
        submissionRepo.deleteAll();
        auditLogRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("KKR Ascendant Fund");
        f.setAgentBank("Wells Fargo");
        facilityId = facilityRepo.save(f).getId();
    }

    private int newSubmissionWithExtraction(String templateFormat, String templateVersion) {
        Submission s = new Submission();
        s.setFacilityId(facilityId);
        s.setAgentBank("Wells Fargo");
        s.setPeriodMonth("2026-05");
        s.setFileName("Agent-BB-May-2026.xlsx");
        s.setStatus("Review");
        int id = submissionRepo.save(s).getId();

        SubmissionExtraction e = new SubmissionExtraction();
        e.setSubmissionId(id);
        e.setTemplateFormat(templateFormat);
        e.setTemplateVersion(templateVersion);
        e.setSheetName("Borrowing Base");
        e.setHeaderRowIndex(9);
        e.setTotalRows(3);
        e.setFlaggedCount(0);
        extractionRepo.save(e);
        return id;
    }

    @Test
    void format_prefersStructurallyRecognisedTemplate() throws Exception {
        int id = newSubmissionWithExtraction("UNKNOWN", "KKR Ascendant Fund");
        mvc.perform(get("/api/submissions/{id}/doc-recognition", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.format").value("Excel Workbook — KKR Ascendant Fund template"));
    }

    @Test
    void format_fallsBackToAgentBankTemplate_whenNoStructuralMatch() throws Exception {
        int id = newSubmissionWithExtraction("CITIBANK", null);
        mvc.perform(get("/api/submissions/{id}/doc-recognition", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.format").value("Excel Workbook — Citibank template"));
    }

    @Test
    void format_unknown_whenNeitherStructuralNorBankRecognised() throws Exception {
        int id = newSubmissionWithExtraction("UNKNOWN", null);
        mvc.perform(get("/api/submissions/{id}/doc-recognition", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.format").value("Excel Workbook — Unknown template"));
    }
}
