package com.ubs.pesubapi.controller;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.entity.SubmissionExtraction;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpAliasRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Parent/child routing across the upload wizard — Phases 3 to 5 of
 * {@code pe-sub-docs/LP_Mapping_and_Database_Architecture.md}.
 *
 * <p>The rule under test is <em>child-first, parent fills gaps</em>: a matched feeder's own values
 * win, and only where the feeder has nothing does the sponsor supply one. That is what makes an
 * unrated feeder inherit its sponsor's rating and advance rate without overwriting the facts the
 * feeder does carry.
 */
class ParentResolutionCommitIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc                        mvc;
    @Autowired ObjectMapper                   mapper;
    @Autowired FacilityRepository             facilityRepo;
    @Autowired SubmissionRepository           submissionRepo;
    @Autowired SubmissionExtractionRepository extractionRepo;
    @Autowired MatchQueueEntryRepository      matchQueueRepo;
    @Autowired LpRecordRepository             lpRecordRepo;
    @Autowired LpMasterRepository             lpMasterRepo;
    @Autowired LpAliasRepository              aliasRepo;

    private static final int HEADER_OFFSET = 7;

    // TEST ONLY — the sponsor holds the credit profile; the feeder is deliberately sparse but
    // carries a region of its own, which the sponsor must not overwrite.
    private static final String SPONSOR = "Apollo Global Management";
    private static final String FEEDER  = "Apollo GRE IV, LP";

    private int facilityId;
    private int submissionId;

    @BeforeEach
    void setup() {
        Facility f = new Facility();
        f.setName("Apollo Global Real Estate Fund IV");   // TEST ONLY
        f.setAgentBank("Goldman Sachs Bank USA");
        facilityId = facilityRepo.save(f).getId();

        Submission s = new Submission();
        s.setFacilityId(facilityId);
        s.setAgentBank("Goldman Sachs Bank USA");
        s.setPeriodMonth("2026-05");
        s.setFileName("Agent-BB-Apollo-GRE-IV-May-2026.xlsx");
        s.setStatus("Extracting");
        s.setWizardStep(3);
        submissionId = submissionRepo.save(s).getId();
    }

    /** Sponsor carrying the full credit profile; feeder carrying only its own region. */
    private LpMaster[] seedHierarchy() {
        LpMaster sponsor = new LpMaster();
        sponsor.setInvestorName(SPONSOR);
        sponsor.setInvestorType("Private Equity");
        sponsor.setInstitutionalOrHnw("Institutional");
        sponsor.setRegionLocation("North America");
        sponsor.setSpRating("A-");
        sponsor.setMoodysRating("A3");
        sponsor.setInvestmentGrade(true);
        sponsor.setAum("$650.0B");
        sponsor.setUbsLpCategory("Rated Investor");
        sponsor.setUbsDefaultAdvanceRate(new BigDecimal("0.9000"));
        sponsor.setUbsDefaultConcentrationLimit(new BigDecimal("7.50"));
        sponsor = lpMasterRepo.save(sponsor);

        LpMaster feeder = new LpMaster();
        feeder.setInvestorName(FEEDER);
        feeder.setParent(SPONSOR);
        feeder.setParentId(sponsor.getId());
        feeder.setRegionLocation("EMEA");        // the feeder's own fact — must survive
        feeder.setSpv(true);
        feeder = lpMasterRepo.save(feeder);

        return new LpMaster[] { sponsor, feeder };
    }

    private void seedExtraction(String... names) {
        ArrayNode rows = mapper.createArrayNode();
        for (int i = 0; i < names.length; i++) {
            ObjectNode row = mapper.createObjectNode();
            row.put("rowIndex", HEADER_OFFSET + i);
            row.put("name", names[i]);
            rows.add(row);
        }
        SubmissionExtraction ext = new SubmissionExtraction();
        ext.setSubmissionId(submissionId);
        ext.setTotalRows(names.length);
        ext.setFlaggedCount(0);
        ext.setExtractedLps(rows);
        extractionRepo.save(ext);
    }

    private void confirmAndCommit() throws Exception {
        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());
        List<MatchQueueEntry> entries = matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId);
        entries.forEach(e -> e.setDecision("Accepted"));
        matchQueueRepo.saveAll(entries);
        mvc.perform(patch("/api/submissions/{id}/shadow-bb-state", submissionId)
                .contentType("application/json").content("{}"))
            .andExpect(status().isOk());
    }

    // ── Phase 3: the resolution rule ─────────────────────────────────────────────────

    @Test
    void acceptedFeederInheritsSponsorProfileButKeepsItsOwnFacts() throws Exception {
        seedHierarchy();
        seedExtraction(FEEDER);
        confirmAndCommit();

        LpRecord stored = lpRecordRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId).getFirst();

        // Gaps filled from the sponsor — this is the whole point of routing.
        assertThat(stored.getSpRating()).isEqualTo("A-");
        assertThat(stored.getMoodysRating()).isEqualTo("A3");
        assertThat(stored.getInvestorType()).isEqualTo("Private Equity");
        assertThat(stored.getAum()).isEqualTo("$650.0B");
        assertThat(stored.getUbsLpCategory()).isEqualTo("Rated Investor");
        assertThat(stored.getUbsAdvanceRate()).isEqualByComparingTo(new BigDecimal("0.9000"));
        assertThat(stored.getUbsConcentrationLimit()).isEqualByComparingTo(new BigDecimal("7.50"));
        assertThat(stored.isInvestmentGrade()).isTrue();

        // The feeder's own region wins over the sponsor's — child-first, not parent-wins.
        assertThat(stored.getRegionLocation()).isEqualTo("EMEA");
        // SPV describes the matched entity itself, so it is read from the feeder, not the chain.
        assertThat(stored.isSpv()).isTrue();
        // Parent names the entity whose profile was applied.
        assertThat(stored.getParent()).isEqualTo(SPONSOR);
    }

    @Test
    void matchedRecordNotParentIsTheAuditLink() throws Exception {
        LpMaster[] seeded = seedHierarchy();
        LpMaster sponsor = seeded[0], feeder = seeded[1];
        seedExtraction(FEEDER);
        confirmAndCommit();

        LpRecord stored = lpRecordRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId).getFirst();
        // The audit trail must keep naming the entity the agent listed, even though the credit
        // profile came from the sponsor.
        assertThat(stored.getLpMasterId()).isEqualTo(feeder.getId());
        assertThat(stored.getLpMasterId()).isNotEqualTo(sponsor.getId());
    }

    @Test
    void matchAgainstAnUltimateEntityAppliesItsOwnProfile() throws Exception {
        seedHierarchy();
        seedExtraction(SPONSOR);
        confirmAndCommit();

        LpRecord stored = lpRecordRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId).getFirst();
        assertThat(stored.getRegionLocation()).isEqualTo("North America");
        assertThat(stored.getSpRating()).isEqualTo("A-");
        // Nothing above it, so no parent is stamped on the record.
        assertThat(stored.getParent()).isNull();
    }

    // ── Phase 4: what Review Matches shows before anything is committed ──────────────

    @Test
    void matchQueueExposesUltimateParentForTheProposedMatch() throws Exception {
        seedHierarchy();
        seedExtraction(FEEDER);

        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());

        // The screen's "Ultimate Parent (To Be Applied)" column is served straight from here, so
        // the analyst sees whose profile an Accept would apply before deciding.
        mvc.perform(get("/api/matching/queue").param("submissionId", String.valueOf(submissionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].agentName").value(FEEDER))
            .andExpect(jsonPath("$[0].masterName").value(FEEDER))
            .andExpect(jsonPath("$[0].masterParent").value(SPONSOR));
    }

    @Test
    void queueResolvesRoutingForEntriesStoredWithoutIt() throws Exception {
        // Entries written before parent routing existed carry no master parent, and entries built
        // before an analyst edited the hierarchy carry a stale one. Resolving on read means the
        // "to be applied" column always states the current truth — and that a null answer
        // unambiguously means "the match is the ultimate entity" rather than "never resolved".
        seedHierarchy();

        MatchQueueEntry legacy = new MatchQueueEntry();
        legacy.setSubmissionId(submissionId);
        legacy.setFacilityId(facilityId);
        legacy.setRowIndex(HEADER_OFFSET);
        legacy.setExtractedName("Apollo Global Real Estate Fnd IV");
        legacy.setMatchedLpName(FEEDER);
        legacy.setMatchScore(89);
        legacy.setNew(false);
        legacy.setDecision("Pending");
        legacy.setMasterParent(null);        // as the pre-routing code left it
        legacy.setMatchedLpMasterId(null);
        matchQueueRepo.save(legacy);

        mvc.perform(get("/api/matching/queue").param("submissionId", String.valueOf(submissionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].masterName").value(FEEDER))
            .andExpect(jsonPath("$[0].masterParent").value(SPONSOR))
            .andExpect(jsonPath("$[0].masterLpId").isNumber());
    }

    @Test
    void queueReportsNullParentForAMatchThatIsItselfUltimate() throws Exception {
        // The other half of the same contract: null here must mean "self", not "unresolved".
        seedHierarchy();

        MatchQueueEntry entry = new MatchQueueEntry();
        entry.setSubmissionId(submissionId);
        entry.setFacilityId(facilityId);
        entry.setRowIndex(HEADER_OFFSET);
        entry.setExtractedName("Apollo Global Mgmt");
        entry.setMatchedLpName(SPONSOR);
        entry.setMatchScore(92);
        entry.setNew(false);
        entry.setDecision("Pending");
        matchQueueRepo.save(entry);

        mvc.perform(get("/api/matching/queue").param("submissionId", String.valueOf(submissionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].masterName").value(SPONSOR))
            .andExpect(jsonPath("$[0].masterParent").doesNotExist());
    }

    @Test
    void overridingTheMatchRerunsParentRouting() throws Exception {
        LpMaster[] seeded = seedHierarchy();
        seedExtraction("Some Unrelated Investor Name That Matches Nothing");

        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());
        int entryId = matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId).getFirst().getId();

        // Manual Search/Override must route exactly as the algorithm's own candidate would.
        mvc.perform(patch("/api/matching/queue/{id}", entryId)
                .contentType("application/json")
                .content("{\"masterName\":\"" + FEEDER + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.masterName").value(FEEDER))
            .andExpect(jsonPath("$.masterParent").value(SPONSOR))
            .andExpect(jsonPath("$.masterLpId").value(seeded[1].getId()));
    }

    // ── Phase 5: the alias feedback loop ─────────────────────────────────────────────

    @Test
    void acceptingAMatchLearnsTheAgentStringAsAnAlias() throws Exception {
        LpMaster[] seeded = seedHierarchy();
        // A spelling that only fuzzy-matches — the alias is what makes it exact next time.
        seedExtraction("Apollo GRE IV LP");
        confirmAndCommit();

        assertThat(aliasRepo.findByUploadedName("APOLLO GRE IV LP"))
            .isPresent()
            .get()
            .extracting(a -> a.getLpMasterId())
            .isEqualTo(seeded[1].getId());
    }

    @Test
    void aKnownAliasAutoAcceptsAtFullConfidenceOnTheNextUpload() throws Exception {
        LpMaster[] seeded = seedHierarchy();
        aliasRepo.save(new com.ubs.pesubapi.entity.LpAlias(seeded[1].getId(), "GRE IV FEEDER"));

        // "GRE IV Feeder" would never clear the fuzzy thresholds against "Apollo GRE IV, LP".
        seedExtraction("GRE IV Feeder");
        mvc.perform(post("/api/submissions/{id}/confirm", submissionId))
            .andExpect(status().isOk());

        mvc.perform(get("/api/matching/queue").param("submissionId", String.valueOf(submissionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].masterName").value(FEEDER))
            .andExpect(jsonPath("$[0].score").value(100))
            .andExpect(jsonPath("$[0].status").value("Accepted"))
            .andExpect(jsonPath("$[0].isNew").value(false))
            // Routing still runs on the alias path — an exact hit is not a shortcut past Phase 3.
            .andExpect(jsonPath("$[0].masterParent").value(SPONSOR));
    }
}
