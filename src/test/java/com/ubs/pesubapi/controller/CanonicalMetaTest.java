package com.ubs.pesubapi.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalMetaTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> canonicalMeta() throws Exception {
        Field f = SubmissionController.class.getDeclaredField("CANONICAL_META");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(null);
    }

    private static String group(Map<String, Object> meta, String key) throws Exception {
        Object entry = meta.get(key);
        assertThat(entry).as("CANONICAL_META missing key: " + key).isNotNull();
        Method m = entry.getClass().getMethod("group");
        return (String) m.invoke(entry);
    }

    private static String canonical(Map<String, Object> meta, String key) throws Exception {
        Object entry = meta.get(key);
        assertThat(entry).as("CANONICAL_META missing key: " + key).isNotNull();
        Method m = entry.getClass().getMethod("canonical");
        return (String) m.invoke(entry);
    }

    // All 10 extraction_keys from V1_2__seed.sql must be present
    private static final List<String> EXTRACTION_KEYS = List.of(
        "INVESTOR_NAME", "LP_CLASSIFICATION", "ELIGIBILITY_FLAG",
        "COMMITMENT", "RECALLABLE_DIST",
        "UNCALLED", "AUM", "NAV",
        "AGENT_RATE", "CONCENTRATION_LIMIT"
    );

    // All non-extractable canonical names that can appear in field mappings
    private static final List<String> CANONICAL_NAMES = List.of(
        "Transferee", "Parent / Sponsor",
        "% of Capital Commitments", "Called Capital",
        "% of Uncalled Capital", "% of LP Called",
        "Pension Assets", "Pension Funded %",
        "Agent Eligible Commitment", "% of Eligible Uncalled",
        "% of Borrowing Base", "Agent Borrowing Base",
        "Excess Concentration",
        "S&P Rating", "Moody's Rating", "Fitch Rating",
        "S&P Numeric Score", "Moody's Numeric Score", "Agent Numeric Rating"
    );

    @Test
    void allExtractionKeysPresentInMeta() throws Exception {
        Map<String, Object> meta = canonicalMeta();
        for (String key : EXTRACTION_KEYS) {
            assertThat(meta).as("Missing extraction_key in CANONICAL_META").containsKey(key);
        }
    }

    @Test
    void allCanonicalNamesPresentInMeta() throws Exception {
        Map<String, Object> meta = canonicalMeta();
        for (String name : CANONICAL_NAMES) {
            assertThat(meta).as("Missing canonical name in CANONICAL_META").containsKey(name);
        }
    }

    @Test
    void investorNameHasCorrectGroupAndCanonical() throws Exception {
        Map<String, Object> meta = canonicalMeta();
        assertThat(group(meta, "INVESTOR_NAME")).isEqualTo("Identity & Classification");
        assertThat(canonical(meta, "INVESTOR_NAME")).isEqualTo("Identity & Classification — Investor Name");
    }

    @Test
    void percentOfBorrowingBaseHasCorrectGroup() throws Exception {
        Map<String, Object> meta = canonicalMeta();
        assertThat(group(meta, "% of Borrowing Base")).isEqualTo("Borrowing Base");
        assertThat(canonical(meta, "% of Borrowing Base")).isEqualTo("Borrowing Base — % of Borrowing Base");
    }

    @Test
    void agentBorrowingBaseHasCorrectGroup() throws Exception {
        Map<String, Object> meta = canonicalMeta();
        assertThat(group(meta, "Agent Borrowing Base")).isEqualTo("Borrowing Base");
        assertThat(canonical(meta, "Agent Borrowing Base")).isEqualTo("Borrowing Base — Agent Borrowing Base");
    }

    @Test
    void borrowingBaseGroupEntriesAreAllCorrect() throws Exception {
        Map<String, Object> meta = canonicalMeta();
        for (String key : List.of("AGENT_RATE", "Agent Eligible Commitment",
                                   "% of Eligible Uncalled", "% of Borrowing Base",
                                   "Agent Borrowing Base")) {
            assertThat(group(meta, key))
                .as("Wrong group for key: " + key)
                .isEqualTo("Borrowing Base");
        }
    }

    @Test
    void noEntryProducesOtherGroup() throws Exception {
        Map<String, Object> meta = canonicalMeta();
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            Method m = e.getValue().getClass().getMethod("group");
            String g = (String) m.invoke(e.getValue());
            assertThat(g)
                .as("Key \"" + e.getKey() + "\" maps to \"Other\" group — add it to CANONICAL_META with the correct group")
                .isNotEqualTo("Other");
        }
    }

    @Test
    void totalEntryCoverageMatchesAllCanonicalFields() throws Exception {
        Map<String, Object> meta = canonicalMeta();
        // 10 extraction_keys + 19 canonical names = 29 entries (one per canonical field)
        assertThat(meta).hasSize(29);
    }
}
