package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.entity.Submission;

import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;

import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Maker-checker independent review of a completed Shadow BB.
 *
 * <p>POST /complete is the maker step: it moves the submission to 'Pending Review' and records the
 * submitter, but does NOT activate the facility. Only a Manager (MANAGER) may accept or reject;
 * a Manager may review their own submission when no second reviewer is available.
 */
class SubmissionCompleteIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc                        mvc;
    @Autowired SubmissionRepository           submissionRepo;
    @Autowired FacilityRepository             facilityRepo;
    @Autowired LpRecordRepository             lpRecordRepo;

    private int facilityId;
    private int submissionId;

    @BeforeEach
    void setup() {
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
        s.setOwnerUuName("alice.analyst");
        s.setOwnerName("Alice Analyst");
        submissionId = submissionRepo.save(s).getId();
    }

    @Test
    void owner_roundTripsThroughApi() throws Exception {
        mvc.perform(get("/api/submissions/{id}", submissionId)
                .with(user("someone").roles("ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ownerUuName").value("alice.analyst"))
            .andExpect(jsonPath("$.ownerName").value("Alice Analyst"));
    }

    @Test
    void abort_byOwner_succeeds() throws Exception {
        mvc.perform(post("/api/submissions/{id}/abort", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isNoContent());
    }

    @Test
    void abort_byOtherAnalyst_isForbidden() throws Exception {
        // Another analyst (not the owner) may not abort a colleague's submission.
        mvc.perform(post("/api/submissions/{id}/abort", submissionId)
                .with(user("m.chen").roles("ANALYST")))
            .andExpect(status().isForbidden());
    }

    @Test
    void abort_byManager_succeeds() throws Exception {
        mvc.perform(post("/api/submissions/{id}/abort", submissionId)
                .with(user("mgr.checker").roles("MANAGER")))
            .andExpect(status().isNoContent());
    }

    @Test
    void nonOwnerAnalyst_cannotSubmitForReview() throws Exception {
        // Concurrency guard: another analyst may not mutate a colleague's submission.
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("m.chen").roles("ANALYST")))
            .andExpect(status().isForbidden());
    }

    @Test
    void takeOver_transfersOwnership_thenPreviousOwnerIsBlocked() throws Exception {
        // M. Chen takes over Alice's submission ...
        mvc.perform(post("/api/submissions/{id}/take-over", submissionId)
                .with(user("m.chen").roles("ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ownerUuName").value("m.chen"));
        // ... Alice (the previous owner) can no longer submit it ...
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isForbidden());
        // ... but the new owner can.
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("m.chen").roles("ANALYST")))
            .andExpect(status().isOk());
    }

    @Test
    void complete_withStaleVersion_isConflict() throws Exception {
        // Optimistic concurrency: a write based on an out-of-date version (e.g. the same submission
        // edited in another tab) is rejected with 409 instead of overwriting newer work.
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .param("expectedVersion", "999")
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isConflict());
    }

    @Test
    void shadowBbState_returnsCurrentVersion_thatCanBeUsedToComplete() throws Exception {
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", submissionId)
                .param("expectedVersion", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"overrides":{"LPRecord-1":{"cls":"Rated Investor"}}}
                    """)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));

        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .param("expectedVersion", "1")
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Pending Review"));
    }

    @Test
    void manager_mayModifyAnothersSubmission() throws Exception {
        // Manager override (RBAC): a Manager may act on any submission without owning it.
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("mgr.checker").roles("MANAGER")))
            .andExpect(status().isOk());
    }

    @Test
    void complete_submitsForReview_withoutActivatingFacility() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Pending Review"))
            .andExpect(jsonPath("$.wizardStep").value(6))
            .andExpect(jsonPath("$.submittedBy").value("alice.analyst"))
            .andExpect(jsonPath("$.facilityName").value("Apex Growth Fund III"));

        // Maker step must NOT activate the facility — that is the checker's decision.
        Facility f = facilityRepo.findById(facilityId).orElseThrow();
        assertThat(f.getStatus())
            .as("Facility must not be Active before acceptance")
            .isNotEqualTo("Active");
        assertThat(f.getLastRunAt())
            .as("lastRunAt must not be stamped before acceptance")
            .isNull();
    }

    @Test
    void accept_byDifferentManager_setsProcessedAndActivatesFacility() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/submissions/{id}/accept", submissionId)
                .with(user("mgr.checker").roles("MANAGER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Processed"))
            .andExpect(jsonPath("$.wizardStep").value(6))
            .andExpect(jsonPath("$.reviewedBy").value("mgr.checker"));

        Facility f = facilityRepo.findById(facilityId).orElseThrow();
        assertThat(f.getStatus()).isEqualTo("Active");
        assertThat(f.getLastRunAt())
            .as("lastRunAt must be stamped on acceptance")
            .isNotNull();
    }

    @Test
    void accept_clearsLiveReclassifiedFlagAfterApproval() throws Exception {
        LpRecord lp = new LpRecord();
        lp.setFacilityId(facilityId);
        lp.setInvestorName("Reclassified Pension");
        lp.setRegionLocation("North America");
        lp.setUbsLpCategory("Rated Investor");
        lp.setReclassified(true);
        int lpId = lpRecordRepo.save(lp).getId();

        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/submissions/{id}/accept", submissionId)
                .with(user("mgr.checker").roles("MANAGER")))
            .andExpect(status().isOk());

        assertThat(lpRecordRepo.findById(lpId).orElseThrow().isReclassified())
            .as("Approved reclassification must no longer be shown as pending")
            .isFalse();
    }

    @Test
    void accept_byTheSubmittingManager_succeeds() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("same.person").roles("MANAGER")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/submissions/{id}/accept", submissionId)
                .with(user("same.person").roles("MANAGER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Processed"))
            .andExpect(jsonPath("$.reviewedBy").value("same.person"));
    }

    @Test
    void accept_byAnalyst_isForbidden() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk());
        // Accept is Manager-only (SecurityConfig): an analyst is denied by authorization.
        mvc.perform(post("/api/submissions/{id}/accept", submissionId)
                .with(user("bob.analyst").roles("ANALYST")))
            .andExpect(status().isForbidden());
    }

    @Test
    void accept_whenNotPendingReview_isConflict() throws Exception {
        // Submission is in 'Review', never submitted for review.
        mvc.perform(post("/api/submissions/{id}/accept", submissionId)
                .with(user("mgr.checker").roles("MANAGER")))
            .andExpect(status().isConflict());
    }

    @Test
    void reject_byManager_returnsToReviewWithReason() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/submissions/{id}/reject", submissionId)
                .with(user("mgr.checker").roles("MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Advance rate for Tier 2 looks wrong\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Review"))
            .andExpect(jsonPath("$.wizardStep").value(5))
            .andExpect(jsonPath("$.reviewNote").value("Advance rate for Tier 2 looks wrong"));

        Facility f = facilityRepo.findById(facilityId).orElseThrow();
        assertThat(f.getStatus())
            .as("Rejected submission must not activate the facility")
            .isNotEqualTo("Active");
    }

    @Test
    void reject_thenResubmitByDifferentUser_reStampsMakerAndClearsNote() throws Exception {
        // A submits, manager rejects with a note.
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/submissions/{id}/reject", submissionId)
                .with(user("mgr.checker").roles("MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Fix Tier 2\"}"))
            .andExpect(status().isOk());

        // A DIFFERENT analyst takes over, then re-submits: the maker re-stamps to them (the current
        // owner) and the stale rejection note clears. Without the take-over the guard would 403.
        mvc.perform(post("/api/submissions/{id}/take-over", submissionId)
                .with(user("bob.analyst").roles("ANALYST")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("bob.analyst").roles("ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Pending Review"))
            .andExpect(jsonPath("$.submittedBy").value("bob.analyst"))
            .andExpect(jsonPath("$.reviewNote").doesNotExist());

        // A Manager may accept their own re-submission when no second reviewer is available.
        mvc.perform(post("/api/submissions/{id}/accept", submissionId)
                .with(user("bob.analyst").roles("MANAGER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reviewedBy").value("bob.analyst"));
    }

    @Test
    void reject_byTheSubmittingManager_succeeds() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("same.person").roles("MANAGER")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/submissions/{id}/reject", submissionId)
                .with(user("same.person").roles("MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Correct the advance rate\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Review"))
            .andExpect(jsonPath("$.reviewedBy").value("same.person"))
            .andExpect(jsonPath("$.reviewNote").value("Correct the advance rate"));
    }

    @Test
    void reject_withoutReason_isBadRequest() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/submissions/{id}/reject", submissionId)
                .with(user("mgr.checker").roles("MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void complete_returns404ForUnknownSubmission() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", 999999)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isNotFound());
    }

    @Test
    void complete_responseContainsFacilityId() throws Exception {
        mvc.perform(post("/api/submissions/{id}/complete", submissionId)
                .with(user("alice.analyst").roles("ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.facilityId").value(facilityId))
            .andExpect(jsonPath("$.agentBank").value("Goldman Sachs"))
            .andExpect(jsonPath("$.periodMonth").value("2026-05"));
    }
}
