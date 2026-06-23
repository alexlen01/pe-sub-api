package com.ubs.pesubapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Pins the commit step (Match Queue → Run Shadow BB) against two regressions:
 *
 * <ol>
 *   <li><b>Dropped rows.</b> The match queue stores each entry's extraction {@code rowIndex}
 *       (the source-sheet row, which starts below the header — e.g. 7 — not at 0). Commit looks
 *       each accepted entry's row up by that same index. A previous mismatch (queue used the
 *       0-based array position) meant the first {@code header-offset} accepted entries found no
 *       row and were silently skipped, so a 900-row file inserted only 893 records.</li>
 *   <li><b>Lost natural order.</b> Inserted LPs must be returnable in the order they appeared in
 *       the uploaded file, not alphabetically.</li>
 * </ol>
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

    private static String agentClassFor(int i) { return AGENT_CLASSES.get(i % AGENT_CLASSES.size()); }

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
                .append(",\"agentClass\":\"").append(agentClassFor(i)).append("\"}");
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

        // Advance step 4 → 5: commits accepted matches to LP Master.
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", submissionId)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        // 1) Every row inserted — none dropped by the header-offset rowIndex mismatch.
        List<Lp> stored = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        assertThat(stored).hasSize(N_ROWS);

        // 2) Natural (source-file) order retained, not alphabetical.
        List<String> storedOrder = stored.stream().map(Lp::getInvestorName).toList();
        assertThat(storedOrder).containsExactlyElementsOf(NAMES);
        assertThat(storedOrder).isNotEqualTo(NAMES.stream().sorted().toList());

        // 3) Agent LP Category preserved verbatim through commit — not dropped, and not
        //    overridden by the invType (Investor Type) default.
        for (int i = 0; i < N_ROWS; i++) {
            assertThat(stored.get(i).getAgentCls()).isEqualTo(agentClassFor(i));
        }

        // And the listing endpoint returns the same natural order and the agent classification.
        mvc.perform(get("/api/lps").param("facilityId", String.valueOf(facilityId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(N_ROWS)))
            .andExpect(jsonPath("$[0].name").value(NAMES.get(0)))
            .andExpect(jsonPath("$[0].agentCls").value(agentClassFor(0)))
            .andExpect(jsonPath("$[" + (N_ROWS - 1) + "].name").value(NAMES.get(N_ROWS - 1)));
    }
}
