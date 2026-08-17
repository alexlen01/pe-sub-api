package com.ubs.pesubapi.service;

import tools.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.repository.LpMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the name-matching hot path. Exercises the optimised
 * prepare/matchBest pair against the legacy matchBestInList to guarantee the
 * candidate-normalisation caching did not change scoring behaviour, plus the
 * duplicate-name case that the "same Agent BB uploaded twice" flow relies on.
 */
class MatchingServiceTest {

    private MatchingService service;

    @BeforeEach
    void setUp() {
        LpMasterRepository lpMasterRepo = mock(LpMasterRepository.class);
        ConfigService configService = mock(ConfigService.class);
        // Empty config → service falls back to documented defaults
        // (autoAccept 95, reviewQueue 80, jwWeight 0.6, levWeight 0.4).
        when(configService.get("matching_config")).thenReturn(Optional.empty());
        // Scoring is independent of the alias and parent-routing collaborators, which only
        // participate in buildMatchQueueEntries; the tests below drive matchBest/analyze directly.
        service = new MatchingService(lpMasterRepo, mock(LpAliasService.class),
            mock(LpMasterResolutionService.class), configService, new ObjectMapper());
    }

    @Test
    void exactNameMatchesWithFullScoreAndAccept() {
        var best = service.matchBestInList("Blue Owl GP Stakes V",
            List.of("Blue Owl GP Stakes V", "Ares Capital Corp"));

        assertThat(best.name()).isEqualTo("Blue Owl GP Stakes V");
        assertThat(best.score()).isEqualTo(100);
        assertThat(best.action()).isEqualTo("Accept");
    }

    @Test
    void caseAndPunctuationNormalisationStillMatches() {
        var best = service.matchBestInList("blue owl, gp stakes v",
            List.of("Blue Owl GP Stakes V"));

        assertThat(best.action()).isEqualTo("Accept");
    }

    @Test
    void emptyCandidateListReturnsNull() {
        assertThat(service.matchBestInList("Blue Owl GP Stakes V", List.of())).isNull();
        assertThat(service.matchBest("Blue Owl GP Stakes V", service.prepare(List.of()))).isNull();
    }

    @Test
    void preparedMatchBestMatchesLegacyMatchBestInList() {
        // This is the second-upload scenario: the master list already contains the
        // names from the first commit, so every extracted row re-matches against the
        // full list. prepare()+matchBest must be identical to the per-call path.
        List<String> candidates = List.of(
            "Blue Owl GP Stakes V", "Blackstone Strategic Partners",
            "Ares Capital Corp", "Apollo Global Management", "KKR North America XIII");
        List<String> inputs = List.of(
            "Blue Owl GP Stakes V", "blackstone strategic partners",
            "Ares Capital", "Unknown Investor LLC", "KKR North America XIII");

        var prepared = service.prepare(candidates);
        for (String input : inputs) {
            var legacy   = service.matchBestInList(input, candidates);
            var prepared0 = service.matchBest(input, prepared);
            assertThat(prepared0.name()).isEqualTo(legacy.name());
            assertThat(prepared0.score()).isEqualTo(legacy.score());
            assertThat(prepared0.action()).isEqualTo(legacy.action());
        }
    }

    @Test
    void exactDuplicateNameResolvesViaFastPath() {
        // The reported regression: re-uploading the same BB. Every name already exists
        // verbatim, so each row must resolve to an exact Accept regardless of list size.
        List<String> candidates = List.of(
            "Blackstone Strategic Partners", "Ares Capital Corp",
            "Blue Owl GP Stakes V", "Apollo Global Management");
        var prepared = service.prepare(candidates);

        var best = service.matchBest("Blue Owl GP Stakes V", prepared);
        assertThat(best.name()).isEqualTo("Blue Owl GP Stakes V");
        assertThat(best.score()).isEqualTo(100);
        assertThat(best.action()).isEqualTo("Accept");
    }

    @Test
    void lengthBandPruningPreservesDecisionsVersusExhaustiveScan() {
        // Guarantees the length-band prefilter never drops a candidate that would change the
        // outcome: for every input, the banded matchBest must agree with a full exhaustive scan
        // on the action and matched name, and on score whenever a real match is found
        // (a below-threshold "New"/NO_MATCH row's reported score may legitimately differ).
        List<String> candidates = List.of(
            "Blue Owl GP Stakes V", "Blue Owl GP Stakes IV", "Blue Owl Capital",
            "Blackstone Strategic Partners", "Blackstone Strategic Partners IX",
            "Ares Capital Corp", "Ares Management", "Apollo Global Management",
            "KKR North America XIII", "Carlyle Partners VIII", "TPG Partners IX",
            "Vista Equity Partners VIII", "Thoma Bravo Fund XV", "Warburg Pincus Global Growth",
            "A", "International Consolidated Diversified Holdings Fund LP");
        List<String> inputs = List.of(
            "Blue Owl GP Stakes V", "blue owl gp stakes iv", "Blue Owl Cap",
            "Blackstone Strategic Partners 9", "Ares Capital", "Ares Mgmt",
            "Apollo Global", "KKR N America XIII", "Carlyle Partners 8",
            "Totally Unrelated Investor", "X", "Vista Equity VIII");

        var prepared = service.prepare(candidates);
        for (String input : inputs) {
            var banded     = service.matchBest(input, prepared);
            var exhaustive = service.matchBestExhaustive(input, prepared);

            assertThat(banded.action())
                .as("action for '%s'", input).isEqualTo(exhaustive.action());
            if (!"New".equals(exhaustive.action())) {
                assertThat(banded.name())
                    .as("matched name for '%s'", input).isEqualTo(exhaustive.name());
                assertThat(banded.score())
                    .as("score for '%s'", input).isEqualTo(exhaustive.score());
            }
        }
    }

    @Test
    void retirementSuffixNormalisationMatchesCanonicalName() {
        // §6.2 step 6: "Ret. Sys." must fold to "retirement system" so an agent abbreviation
        // resolves to the canonical LP Master spelling. Both normalise identically → exact accept.
        var best = service.matchBestInList("Texas Teachers Ret. Sys.",
            List.of("Texas Teachers Retirement System", "Ares Capital Corp"));

        assertThat(best.name()).isEqualTo("Texas Teachers Retirement System");
        assertThat(best.score()).isEqualTo(100);
        assertThat(best.action()).isEqualTo("Accept");
        assertThat(best.band()).isEqualTo(MatchingService.Band.AUTO_ACCEPT);

        // The bare "Ret." form folds to "retirement" as well.
        var bare = service.matchBestInList("Maple Ret.", List.of("Maple Retirement"));
        assertThat(bare.score()).isEqualTo(100);
    }

    @Test
    void fourBandClassificationSpansAcceptReviewAndNoMatch() {
        // One exact name (Accept) and one unrelated name (queued as a potential new LP — per §6.4
        // no candidate is ever auto-rejected) confirm the band boundaries are wired through to
        // MatchCandidate.band().
        var exact = service.matchBestInList("Ares Capital Corp",
            List.of("Ares Capital Corp", "Blue Owl GP Stakes V"));
        assertThat(exact.band()).isEqualTo(MatchingService.Band.AUTO_ACCEPT);

        var unrelated = service.matchBestInList("Zzzz Qqqq Xxxx",
            List.of("Ares Capital Corp", "Blue Owl GP Stakes V"));
        assertThat(unrelated.band()).isEqualTo(MatchingService.Band.NO_MATCH);
        assertThat(unrelated.action()).isEqualTo("New");
    }

    @Test
    void analyzeReturnsRankedTopCandidatesWithPerMetricBreakdown() {
        List<String> candidates = List.of(
            "Blue Owl GP Stakes V", "Blue Owl GP Stakes IV", "Blue Owl Capital",
            "Ares Capital Corp", "Apollo Global Management", "KKR North America XIII");
        var prepared = service.prepare(candidates);

        var analysis = service.analyze("Blue Owl GP Stakes V", prepared, 5);

        assertThat(analysis.normalized()).isEqualTo("blue owl gp stakes v");
        assertThat(analysis.band()).isEqualTo(MatchingService.Band.AUTO_ACCEPT);
        assertThat(analysis.candidates()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        // Top candidate is the verbatim match with a full combined score and populated metrics.
        var top = analysis.candidates().getFirst();
        assertThat(top.name()).isEqualTo("Blue Owl GP Stakes V");
        assertThat(top.combined()).isEqualTo(100);
        assertThat(top.jw()).isBetween(0, 100);
        assertThat(top.lev()).isBetween(0, 100);
        // Ranked strictly non-increasing by combined score.
        var combined = analysis.candidates().stream().map(candidate -> candidate.combined()).toList();
        for (int i = 1; i < combined.size(); i++)
            assertThat(combined.get(i)).isLessThanOrEqualTo(combined.get(i - 1));
    }

    @Test
    void analyzeOnEmptyCandidateListIsNoMatch() {
        var analysis = service.analyze("Blue Owl GP Stakes V", service.prepare(List.of()), 5);
        assertThat(analysis.band()).isEqualTo(MatchingService.Band.NO_MATCH);
        assertThat(analysis.candidates()).isEmpty();
    }
}
