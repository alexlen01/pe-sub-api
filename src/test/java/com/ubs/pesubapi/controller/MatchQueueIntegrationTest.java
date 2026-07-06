package com.ubs.pesubapi.controller;

import tools.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.entity.SubmissionExtraction;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRateRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exercises the LP name-matching algorithm end-to-end: POST /confirm builds the match queue from the
 * stored extraction, and GET /matching/queue must return the four-band decision (§6.4) plus the
 * persisted match_details breakdown (§6.5) for each row. Also pins the retirement-suffix
 * normalisation gap (§6.2 step 6): "Ret. Sys." must resolve to the canonical LP Master spelling.
 */
class MatchQueueIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc                        mvc;
    @Autowired ObjectMapper                   mapper;
    @Autowired FacilityRepository             facilityRepo;
    @Autowired LpMasterRepository             lpMasterRepo;
    @Autowired SubmissionRepository           submissionRepo;
    @Autowired SubmissionExtractionRepository extractionRepo;
    @Autowired MatchQueueEntryRepository      matchQueueRepo;
    @Autowired LpRecordRepository             lpRecordRepo;
    @Autowired LpRateRepository               rateRepo;
    @Autowired AuditLogRepository             auditLogRepo;

    private int facilityId;
    private int submissionId;

    @BeforeEach
    void setup() {
        matchQueueRepo.deleteAll();
        extractionRepo.deleteAll();
        submissionRepo.deleteAll();
        rateRepo.deleteAll();
        lpRecordRepo.deleteAll();
        auditLogRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("Apex Growth Fund III");
        f.setAgentBank("Goldman Sachs");
        facilityId = facilityRepo.save(f).getId();

        LpRecord lpRecord = new LpRecord();
        lpRecord.setFacilityId(facilityId);
        lpRecord.setInvestorName("Texas Teachers Retirement System");
        lpRecord.setInvType("Pension");
        lpRecord.setRegion("US");
        lpRecord.setCls("Rated");
        lpRecordRepo.save(lpRecord);

        LpMaster master = new LpMaster();
        master.setInvestorName("Texas Teachers Retirement System");   // TEST ONLY
        lpMasterRepo.save(master);

        Submission s = new Submission();
        s.setFacilityId(facilityId);
        s.setAgentBank("Goldman Sachs");
        s.setPeriodMonth("2026-05");
        s.setFileName("bb_may26.xlsx");
        s.setStatus("Extracting");
        s.setWizardStep(3);
        submissionId = submissionRepo.save(s).getId();
    }

    private void seedExtraction(String... names) {
        SubmissionExtraction ext = new SubmissionExtraction();
        ext.setSubmissionId(submissionId);
        ext.setTotalRows(names.length);
        ext.setFlaggedCount(0);
        ext.setExtractedLps(extractedRows(names));
        extractionRepo.save(ext);
    }

    private ArrayNode extractedRows(String... names) {
        ArrayNode rows = mapper.createArrayNode();
        Arrays.stream(names)
            .map(this::extractedRow)
            .forEach(rows::add);
        return rows;
    }

    private ObjectNode extractedRow(String name) {
        ObjectNode row = mapper.createObjectNode();
        row.put("name", name);
        return row;
    }

    @Test
    void confirmBuildsQueueWithBandedDecisionsAndMatchDetails() throws Exception {
        // Row 0: agent abbreviation that must normalise to the canonical LP Master name (auto-accept).
        // Row 1: an unrelated name with no candidate (no-match → potential new LP).
        seedExtraction("Texas Teachers Ret. Sys.", "Brand New Investor XYZ");

        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());

        mvc.perform(get("/api/matching/queue").param("submissionId", String.valueOf(submissionId)))
            .andExpect(status().isOk())
            // ── Row 0: retirement-suffix normalisation → exact auto-accept ─────────────
            .andExpect(jsonPath("$[0].agentName").value("Texas Teachers Ret. Sys."))
            .andExpect(jsonPath("$[0].masterName").value("Texas Teachers Retirement System"))
            .andExpect(jsonPath("$[0].score").value(100))
            .andExpect(jsonPath("$[0].decision").value("Accepted"))
            .andExpect(jsonPath("$[0].isNew").value(false))
            .andExpect(jsonPath("$[0].reasons[0]").exists())
            // ── Row 0 match_details payload (§6.5) ─────────────────────────────────────
            .andExpect(jsonPath("$[0].matchDetails.band").value("AUTO_ACCEPT"))
            .andExpect(jsonPath("$[0].matchDetails.normalized").value("texas teachers retirement system"))
            .andExpect(jsonPath("$[0].matchDetails.candidates[0].name").value("Texas Teachers Retirement System"))
            .andExpect(jsonPath("$[0].matchDetails.candidates[0].combined").value(100))
            .andExpect(jsonPath("$[0].matchDetails.candidates[0].jw").isNumber())
            .andExpect(jsonPath("$[0].matchDetails.candidates[0].lev").isNumber())
            // ── Row 1: no match → queued as a potential new LP ─────────────────────────
            .andExpect(jsonPath("$[1].agentName").value("Brand New Investor XYZ"))
            .andExpect(jsonPath("$[1].isNew").value(true))
            .andExpect(jsonPath("$[1].decision").value("Pending"))
            .andExpect(jsonPath("$[1].matchDetails.band").value("NO_MATCH"));
    }
}
