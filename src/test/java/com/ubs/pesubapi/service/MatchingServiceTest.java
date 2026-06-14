package com.ubs.pesubapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.repository.LpRepository;
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
        LpRepository lpRepo = mock(LpRepository.class);
        ConfigService configService = mock(ConfigService.class);
        // Empty config → service falls back to documented defaults
        // (autoAccept 95, reviewQueue 80, jwWeight 0.6, levWeight 0.4).
        when(configService.get("matching_config")).thenReturn(Optional.empty());
        service = new MatchingService(lpRepo, configService, new ObjectMapper());
    }

    @Test
    void exactNameMatchesWithFullScoreAndAccept() {
        var best = service.matchBestInList("Blue Owl GP Stakes V",
            List.of("Blue Owl GP Stakes V", "Ares Capital Corp"));

        assertThat(best).isNotNull();
        assertThat(best.name()).isEqualTo("Blue Owl GP Stakes V");
        assertThat(best.score()).isEqualTo(100);
        assertThat(best.action()).isEqualTo("Accept");
    }

    @Test
    void caseAndPunctuationNormalisationStillMatches() {
        var best = service.matchBestInList("blue owl, gp stakes v",
            List.of("Blue Owl GP Stakes V"));

        assertThat(best).isNotNull();
        assertThat(best.action()).isEqualTo("Accept");
    }

    @Test
    void unrelatedNameIsRejected() {
        var best = service.matchBestInList("Completely Different Fund",
            List.of("Blue Owl GP Stakes V"));

        assertThat(best).isNotNull();
        assertThat(best.action()).isEqualTo("Reject");
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
        // (a below-threshold "Reject" row's reported score may legitimately differ).
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
            if (!"Reject".equals(exhaustive.action())) {
                assertThat(banded.name())
                    .as("matched name for '%s'", input).isEqualTo(exhaustive.name());
                assertThat(banded.score())
                    .as("score for '%s'", input).isEqualTo(exhaustive.score());
            }
        }
    }
}
