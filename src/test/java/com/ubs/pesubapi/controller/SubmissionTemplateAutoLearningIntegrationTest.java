package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.BbTemplate;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.entity.SubmissionExtraction;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubmissionTemplateAutoLearningIntegrationTest extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired FacilityRepository facilities;
    @Autowired SubmissionRepository submissions;
    @Autowired SubmissionExtractionRepository extractions;
    @Autowired BbTemplateRepository templates;

    @Test
    void confirm_recognizedPreloadedTemplate_doesNotCreateAgentNamedTemplate() throws Exception {
        long before = templates.count();

        BbTemplate recognized = new BbTemplate();
        recognized.setTemplateName("AEP VII");
        recognized.setTemplateSlug("aep-vii");
        recognized.setAutoLearned(false);
        templates.save(recognized);
        int submissionId = submission("Wells Fargo", "AEP VII");

        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.templateSaved").value(false));

        assertThat(templates.findAllByTemplateNameIgnoreCase("Wells Fargo")).isEmpty();
        assertThat(templates.count()).isEqualTo(before + 1);
    }

    @Test
    void confirm_unrecognizedTemplate_doesNotMutateTemplateRegistry() throws Exception {
        long before = templates.count();
        int submissionId = submission("New Agent Bank", null);

        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.templateSaved").value(false));

        assertThat(templates.findAllByTemplateNameIgnoreCase("New Agent Bank")).isEmpty();
        assertThat(templates.count()).isEqualTo(before);
    }

    private int submission(String agentBank, String recognizedVersion) {
        Facility facility = new Facility();
        facility.setName("Template Recognition Test Fund " + agentBank); // TEST ONLY
        facility.setAgentBank(agentBank);
        int facilityId = facilities.save(facility).getId();

        Submission submission = new Submission();
        submission.setFacilityId(facilityId);
        submission.setAgentBank(agentBank);
        submission.setPeriodMonth("2026-07");
        submission.setFileName("Agent-BB-Test.xlsx");
        submission.setStatus("Extracting");
        submission.setWizardStep(3);
        int id = submissions.save(submission).getId();

        SubmissionExtraction extraction = new SubmissionExtraction();
        extraction.setSubmissionId(id);
        extraction.setTemplateVersion(recognizedVersion);
        extraction.setSheetName("Borrowing Base");
        extraction.setHeaderRowIndex(8);
        extraction.setTotalRows(0);
        extraction.setFlaggedCount(0);
        extraction.setExtractedLps(mapper.createArrayNode());
        extraction.setFieldMappings(mapper.createArrayNode());
        extraction.setUnrecognizedColumns(mapper.createArrayNode());
        extractions.save(extraction);
        return id;
    }
}
