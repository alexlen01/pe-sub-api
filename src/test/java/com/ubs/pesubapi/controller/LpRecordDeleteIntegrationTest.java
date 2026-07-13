package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Manual correction path for facility LP records: rows that slipped through extraction review
 * (and reviewer/manager checks) must be deletable from the LP Master screen. The delete must
 * recompute the facility's ranks over the remaining population — rank is API-owned — and must
 * detach (not delete) match-queue entries that point at the removed record.
 */
class LpRecordDeleteIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc                   mvc;
    @Autowired FacilityRepository        facilityRepo;
    @Autowired LpRecordRepository        lpRecordRepo;
    @Autowired MatchQueueEntryRepository matchQueueRepo;
    @Autowired SubmissionRepository      submissionRepo;
    @Autowired AuditLogRepository        auditLogRepo;

    private int facilityId;
    private int bigId;      // $30M uncalled → rank 1
    private int middleId;   // $20M uncalled → rank 2 (the erroneous row we delete)
    private int smallId;    // $10M uncalled → rank 3

    @BeforeEach
    void seed() {
        Facility f = new Facility();
        f.setName("Apex Growth Fund V");   // TEST ONLY
        f.setAgentBank("Morgan Stanley");
        facilityId = facilityRepo.save(f).getId();

        bigId    = lp("Big Fund LP",    "30000000").getId();
        middleId = lp("Erroneous Row LP", "20000000").getId();
        smallId  = lp("Small Fund LP",  "10000000").getId();
    }

    private LpRecord lp(String name, String uncalledDollars) {
        LpRecord r = new LpRecord();
        r.setFacilityId(facilityId);
        r.setInvestorName(name);   // TEST ONLY
        r.setInvType("Pension");
        r.setRegion("US");
        r.setCls("Rated");
        r.setUc("$" + uncalledDollars);
        r.setUcNum(new BigDecimal(uncalledDollars));
        return lpRecordRepo.save(r);
    }

    @Test
    void delete_removesRecord_andRecomputesRanksOverRemainingPopulation() throws Exception {
        mvc.perform(delete("/api/lpRecords/{id}", middleId))
            .andExpect(status().isNoContent());

        assertThat(lpRecordRepo.findById(middleId)).isEmpty();
        assertThat(lpRecordRepo.findById(bigId).orElseThrow().getRank()).isEqualTo(1);
        assertThat(lpRecordRepo.findById(smallId).orElseThrow().getRank()).isEqualTo(2);

        assertThat(auditLogRepo.findAllByOrderByCreatedAtDesc())
            .anySatisfy(entry -> {
                assertThat(entry.getEvent()).isEqualTo("LP Record Deleted");
                assertThat(entry.getDetail()).contains("Erroneous Row LP");
                assertThat(entry.getFacilityId()).isEqualTo(facilityId);
            });
    }

    @Test
    void delete_detachesMatchQueueEntries_keepingTheirDecisionHistory() throws Exception {
        Submission s = new Submission();
        s.setFacilityId(facilityId);
        s.setAgentBank("Morgan Stanley");
        s.setPeriodMonth("2026-06");
        s.setFileName("bb_jun26.xlsx");   // TEST ONLY
        s.setStatus("Matching");
        s.setWizardStep(4);
        int submissionId = submissionRepo.save(s).getId();

        MatchQueueEntry entry = new MatchQueueEntry();
        entry.setSubmissionId(submissionId);
        entry.setFacilityId(facilityId);
        entry.setRowIndex(0);
        entry.setExtractedName("Erroneous Row LP");
        entry.setMatchedLpId(middleId);
        entry.setDecision("accepted");
        int entryId = matchQueueRepo.save(entry).getId();

        mvc.perform(delete("/api/lpRecords/{id}", middleId))
            .andExpect(status().isNoContent());

        MatchQueueEntry detached = matchQueueRepo.findById(entryId).orElseThrow();
        assertThat(detached.getMatchedLpId()).isNull();
        assertThat(detached.getDecision()).isEqualTo("accepted");
    }

    @Test
    void delete_unknownId_returns404() throws Exception {
        mvc.perform(delete("/api/lpRecords/{id}", 999_999))
            .andExpect(status().isNotFound());
        assertThat(lpRecordRepo.count()).isEqualTo(3);
    }
}
