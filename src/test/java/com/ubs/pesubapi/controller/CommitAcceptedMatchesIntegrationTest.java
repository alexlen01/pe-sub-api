package com.ubs.pesubapi.controller;

import tools.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.entity.SubmissionExtraction;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pins the commit step (Match Queue → Run Shadow BB).
 *
 * <ul>
 *   <li><b>All-rows insertion.</b> Every extracted row is inserted regardless of its match queue
 *       decision (Accepted, Rejected, Pending, or absent). Previously Pending entries were
 *       silently skipped, so a 900-row file with 892 unresolved rows inserted only 8 records.</li>
 *   <li><b>Row-index alignment.</b> The match queue stores each entry's extraction
 *       {@code rowIndex} (the source-sheet row, not the 0-based array position). A prior bug
 *       mismatched these, causing the first header-offset accepted entries to find no row and
 *       be dropped.</li>
 *   <li><b>Natural order.</b> Inserted LPs are ordered by their source-file position, not
 *       alphabetically.</li>
 *   <li><b>Delete-replace.</b> A re-run replaces existing facility LP records rather than
 *       merging, so stale records from the previous upload are not carried forward.</li>
 * </ul>
 */
class CommitAcceptedMatchesIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc                        mvc;
    @Autowired ObjectMapper                   mapper;
    @Autowired FacilityRepository             facilityRepo;
    @Autowired SubmissionRepository           submissionRepo;
    @Autowired SubmissionExtractionRepository extractionRepo;
    @Autowired MatchQueueEntryRepository      matchQueueRepo;
    @Autowired LpRepository                   lpRepo;

    private static final int HEADER_OFFSET = 7;   // header at sheet row 6 → data rowIndex starts at 7
    private static final int N_ROWS        = 12;

    // Names deliberately in reverse-alphabetical order so source order ≠ alphabetical order.
    private static final List<String> NAMES = IntStream.range(0, N_ROWS)
        .mapToObj(i -> String.format("Investor %02d", N_ROWS - 1 - i))
        .collect(Collectors.toList());

    // Agent LP Category values (verbatim from the Agent BB) — distinct from Investor Type.
    // Cycled per row so each stored LP must carry its own value; proves the commit path persists
    // the agent LP category rather than dropping it or replacing it with the invType default.
    private static final List<String> AGENT_CLASSES = List.of(
        "Pension Fund", "Designated PWM", "Rated Included",
        "Non-Rated Included", "Designated Institutional", "Investment Consultant");
    private static final List<String> INVESTOR_TYPES = List.of(
        "Public Pension", "Family Office", "Endowment", "Insurance Company");

    private static String agentClassFor(int i) { return AGENT_CLASSES.get(i % AGENT_CLASSES.size()); }
    private static String investorTypeFor(int i) { return INVESTOR_TYPES.get(i % INVESTOR_TYPES.size()); }

    private int facilityId;
    private int submissionId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Blue Owl GP Stakes V");   // TEST ONLY
        f.setAgentBank("Goldman Sachs Bank USA");
        facilityId = facilityRepo.save(f).getId();

        Submission s = new Submission();
        s.setFacilityId(facilityId);
        s.setAgentBank("Goldman Sachs Bank USA");
        s.setPeriodMonth("2026-05");
        s.setFileName("Agent-BB-Blue-Owl-GP-Stakes-V-May-2026.xlsx");
        s.setStatus("Extracting");
        s.setWizardStep(3);
        submissionId = submissionRepo.save(s).getId();

        // Extracted LPs as stored by the pipeline: rowIndex = source-sheet row (header-offset).
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < N_ROWS; i++) {
            if (i > 0) json.append(',');
            json.append("{\"rowIndex\":").append(HEADER_OFFSET + i)
                .append(",\"name\":\"").append(NAMES.get(i)).append("\"")
                .append(",\"agentClass\":\"").append(agentClassFor(i)).append("\"")
                .append(",\"investorType\":\"").append(investorTypeFor(i)).append("\"}");
        }
        json.append(']');

        SubmissionExtraction ext = new SubmissionExtraction();
        ext.setSubmissionId(submissionId);
        ext.setTotalRows(N_ROWS);
        ext.setFlaggedCount(0);
        try {
            ext.setExtractedLps(mapper.readTree(json.toString()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        extractionRepo.save(ext);
    }

    @SuppressWarnings("null")
    @Test
    void commit_insertsEveryAcceptedRow_inNaturalOrder() throws Exception {
        // Build the match queue (all rows are new → NO_MATCH on an empty LP Master).
        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());

        // Credit officer accepts every queued row.
        List<MatchQueueEntry> entries = matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId);
        assertThat(entries).hasSize(N_ROWS);
        entries.forEach(e -> e.setDecision("Accepted"));
        matchQueueRepo.saveAll(entries);

        // Advance step 4 → 5: commits resolved decisions to LP Master.
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", submissionId)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        // 1) Every row inserted — none dropped by the header-offset rowIndex mismatch.
        List<Lp> stored = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        assertThat(stored).hasSize(N_ROWS);

        // 2) Natural (source-file) order retained, not alphabetical.
        List<String> storedOrder = stored.stream().map(lp -> lp.getInvestorName()).toList();
        assertThat(storedOrder).containsExactlyElementsOf(NAMES);
        assertThat(storedOrder).isNotEqualTo(NAMES.stream().sorted().toList());

        // 3) Agent LP Category preserved verbatim through commit — not dropped, and not
        //    overridden by the invType (Investor Type) default.
        for (int i = 0; i < N_ROWS; i++) {
            assertThat(stored.get(i).getAgentCls()).isEqualTo(agentClassFor(i));
            assertThat(stored.get(i).getInvestorType()).isEqualTo(investorTypeFor(i));
        }

        // And the listing endpoint returns the same natural order plus mapped classification fields.
        mvc.perform(get("/api/lps").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(N_ROWS)))
            .andExpect(jsonPath("$[0].name").value(NAMES.get(0)))
            .andExpect(jsonPath("$[0].agentCls").value(agentClassFor(0)))
            .andExpect(jsonPath("$[0].investor_type").value(investorTypeFor(0)))
            .andExpect(jsonPath("$[" + (N_ROWS - 1) + "].name").value(NAMES.get(N_ROWS - 1)));
    }

    @SuppressWarnings("null")
    @Test
    void commit_insertsPendingRows_asNew() throws Exception {
        // Build the match queue — all rows are NO_MATCH (LP Master is empty) so all start Pending.
        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());

        // Do NOT change any decisions — all remain Pending.
        List<MatchQueueEntry> entries = matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId);
        assertThat(entries).hasSize(N_ROWS);
        assertThat(entries).allMatch(e -> "Pending".equals(e.getDecision()));

        // Advance step 4 → 5 without resolving any decisions.
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", submissionId)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        // All rows must be inserted — none dropped because decision is Pending.
        List<Lp> stored = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        assertThat(stored).hasSize(N_ROWS);
        List<String> storedNames = stored.stream().map(lp -> lp.getInvestorName()).toList();
        assertThat(storedNames).containsExactlyElementsOf(NAMES);
    }

    @SuppressWarnings("null")
    @Test
    void commit_sameUploadTwice_updatesExistingRowsWithoutDuplicateKey() throws Exception {
        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());
        matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId)
            .forEach(e -> { e.setDecision("Accepted"); matchQueueRepo.save(e); });
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", submissionId)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        List<Integer> firstIds = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId).stream()
            .map(Lp::getId)
            .toList();
        assertThat(firstIds).hasSize(N_ROWS);

        Submission s2 = new Submission();
        s2.setFacilityId(facilityId);
        s2.setAgentBank("Goldman Sachs Bank USA");
        s2.setPeriodMonth("2026-06");
        s2.setFileName("Agent-BB-Blue-Owl-GP-Stakes-V-June-2026.xlsx");
        s2.setStatus("Extracting");
        s2.setWizardStep(3);
        int sub2Id = submissionRepo.save(s2).getId();

        SubmissionExtraction ext2 = new SubmissionExtraction();
        ext2.setSubmissionId(sub2Id);
        ext2.setTotalRows(N_ROWS);
        ext2.setFlaggedCount(0);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < N_ROWS; i++) {
            if (i > 0) json.append(',');
            json.append("{\"rowIndex\":").append(HEADER_OFFSET + i)
                .append(",\"name\":\"").append(NAMES.get(i)).append("\"")
                .append(",\"agentClass\":\"").append(agentClassFor(i)).append("\"")
                .append(",\"investorType\":\"").append(investorTypeFor(i)).append("\"}");
        }
        json.append(']');
        ext2.setExtractedLps(mapper.readTree(json.toString()));
        extractionRepo.save(ext2);

        mvc.perform(post("/api/submissions/{id}/confirm", sub2Id))
            .andExpect(status().isOk());
        matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(sub2Id)
            .forEach(e -> { e.setDecision("Accepted"); matchQueueRepo.save(e); });
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", sub2Id)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        List<Lp> stored = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        assertThat(stored).hasSize(N_ROWS);
        assertThat(stored.stream().map(Lp::getId).toList()).containsExactlyElementsOf(firstIds);
        assertThat(stored.stream().map(Lp::getInvestorName).toList()).containsExactlyElementsOf(NAMES);
        assertThat(stored.get(0).getInvestorType()).isEqualTo(investorTypeFor(0));
        assertThat(stored.get(0).getAgentCls()).isEqualTo(agentClassFor(0));
    }

    @SuppressWarnings("null")
    @Test
    void commit_replacesExistingFacilityLps_onRerun() throws Exception {
        // First commit: all rows accepted → inserts N_ROWS LPs.
        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());
        matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId)
            .forEach(e -> { e.setDecision("Accepted"); matchQueueRepo.save(e); });
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", submissionId)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());
        assertThat(lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId)).hasSize(N_ROWS);

        // Second submission for the same facility with a different 3-row extraction.
        Submission s2 = new Submission();
        s2.setFacilityId(facilityId);
        s2.setAgentBank("Goldman Sachs Bank USA");
        s2.setPeriodMonth("2026-06");
        s2.setFileName("Agent-BB-June-2026.xlsx");
        s2.setStatus("Extracting");
        s2.setWizardStep(3);
        int sub2Id = submissionRepo.save(s2).getId();

        SubmissionExtraction ext2 = new SubmissionExtraction();
        ext2.setSubmissionId(sub2Id);
        ext2.setTotalRows(3);
        ext2.setFlaggedCount(0);
        ext2.setExtractedLps(mapper.readTree("""
            [
              {"rowIndex":7,"name":"New Fund Alpha"},
              {"rowIndex":8,"name":"New Fund Beta"},
              {"rowIndex":9,"name":"New Fund Gamma"}
            ]"""));
        extractionRepo.save(ext2);

        mvc.perform(post("/api/submissions/{id}/confirm", sub2Id))
            .andExpect(status().isOk());
        matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(sub2Id)
            .forEach(e -> { e.setDecision("Accepted"); matchQueueRepo.save(e); });
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", sub2Id)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        // Facility must now have exactly 3 LPs from the second submission — old 12 are gone.
        List<Lp> stored = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        assertThat(stored).hasSize(3);
        assertThat(stored.stream().map(lp -> lp.getInvestorName()).toList())
            .containsExactly("New Fund Alpha", "New Fund Beta", "New Fund Gamma");
    }

    @SuppressWarnings("null")
    @Test
    void commit_usesMatchedLpMasterName_andEnrichesEmptyFieldsFromLpMaster() throws Exception {
        Facility otherFacility = new Facility();
        otherFacility.setName("Different Levered Facility");
        otherFacility.setAgentBank("Goldman Sachs Bank USA");
        int otherFacilityId = facilityRepo.save(otherFacility).getId();

        Lp matchedElsewhere = new Lp();
        matchedElsewhere.setFacilityId(otherFacilityId);
        matchedElsewhere.setInvestorName("Texas Teachers Retirement System");
        matchedElsewhere.setParent("Do Not Copy Parent");
        matchedElsewhere.setInvType("Pension");
        matchedElsewhere.setRegion("US");
        matchedElsewhere.setCls("Rated");
        matchedElsewhere.setCapCommit("$99.0M");
        int matchedElsewhereId = lpRepo.save(matchedElsewhere).getId();

        SubmissionExtraction ext = extractionRepo.findBySubmissionId(submissionId).orElseThrow();
        ext.setExtractedLps(mapper.readTree("""
            [
              {
                "rowIndex": 7,
                "name": "Texas Teachers Ret. Sys.",
                "canonicalFields": {
                  "Investor Type": "Public Pension",
                  "LP Category": "Pension Fund"
                },
                "commit": "$10.0M",
                "uncalled": "$4.0M"
              },
              {
                "rowIndex": 8,
                "name": "Texas Teachers Ret. Sys. Sidecar",
                "agentClass": "Designated Institutional",
                "commit": "$5.0M",
                "uncalled": "$2.0M"
              }
            ]
            """));
        extractionRepo.save(ext);

        // Accepted entries use the LP Master name; empty fields are enriched from LP Master when
        // a record is found there. In this test the matched LP is in lpRepo (not lpMasterRepo),
        // so lpMasterRepo returns empty and no baseline is applied — extraction fields only.
        // Rejected entries use the extracted Agent BB name and receive no LP Master enrichment.
        MatchQueueEntry accepted = new MatchQueueEntry();
        accepted.setSubmissionId(submissionId);
        accepted.setFacilityId(facilityId);
        accepted.setRowIndex(7);
        accepted.setExtractedName("Texas Teachers Ret. Sys.");
        accepted.setMatchedLpId(matchedElsewhereId);
        accepted.setMatchedLpName("Texas Teachers Retirement System");
        accepted.setMatchScore(100);
        accepted.setNew(false);
        accepted.setDecision("Accepted");

        MatchQueueEntry rejected = new MatchQueueEntry();
        rejected.setSubmissionId(submissionId);
        rejected.setFacilityId(facilityId);
        rejected.setRowIndex(8);
        rejected.setExtractedName("Texas Teachers Ret. Sys. Sidecar");
        rejected.setMatchedLpName("Texas Teachers Retirement System");
        rejected.setMatchScore(92);
        rejected.setNew(false);
        rejected.setDecision("Rejected");

        matchQueueRepo.saveAll(List.of(accepted, rejected));

        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", submissionId)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        List<Lp> stored = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        assertThat(stored).hasSize(2);
        assertThat(stored.stream().map(lp -> lp.getInvestorName()).toList()).containsExactly(
            "Texas Teachers Retirement System",
            "Texas Teachers Ret. Sys. Sidecar"
        );

        Lp acceptedLp = stored.get(0);
        assertThat(acceptedLp.getParent()).isNull();
        assertThat(acceptedLp.getInvType()).isEqualTo("Institutional");
        assertThat(acceptedLp.getCls()).isEqualTo("Eligible");
        assertThat(acceptedLp.getCapCommit()).isEqualTo("$10.0M");
        assertThat(acceptedLp.getUc()).isEqualTo("$4.0M");
        assertThat(acceptedLp.getAgentCls()).isEqualTo("Pension Fund");
        assertThat(acceptedLp.getInvestorType()).isEqualTo("Public Pension");

        Lp rejectedNewLp = stored.get(1);
        assertThat(rejectedNewLp.getInvType()).isEqualTo("Institutional");
        assertThat(rejectedNewLp.getCls()).isEqualTo("Eligible");
        assertThat(rejectedNewLp.getCapCommit()).isEqualTo("$5.0M");
        assertThat(rejectedNewLp.getUc()).isEqualTo("$2.0M");
        assertThat(rejectedNewLp.getAgentCls()).isEqualTo("Designated Institutional");

        Lp untouchedMatchedRecord = lpRepo.findById(matchedElsewhereId).orElseThrow();
        assertThat(untouchedMatchedRecord.getFacilityId()).isEqualTo(otherFacilityId);
        assertThat(untouchedMatchedRecord.getParent()).isEqualTo("Do Not Copy Parent");
        assertThat(untouchedMatchedRecord.getCapCommit()).isEqualTo("$99.0M");
        assertThat(untouchedMatchedRecord.getAgentCls()).isNull();
    }
}
