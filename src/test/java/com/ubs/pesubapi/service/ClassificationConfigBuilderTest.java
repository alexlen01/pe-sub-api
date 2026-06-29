package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads against the seeded BB template registry.
 * KKR Ascendant Fund (Class A) carries 6 group-header classification rows.
 * Templates not yet seeded (WF Blue Owl, Petershill IV) are tested via @Disabled stubs.
 */
class ClassificationConfigBuilderTest extends IntegrationTestBase {

    @Autowired ClassificationConfigBuilder builder;
    @Autowired ObjectMapper                mapper;

    @Test
    void buildJson_mapsSeededGroupHeadersToAgentClassification() throws Exception {
        String json = builder.buildJson("KKR Ascendant Fund");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        assertThat(config)
            .hasSize(6)
            .containsEntry("Rated Included Investors",     "Rated Included")
            .containsEntry("Non-Rated Included Investors", "Non-Rated Included")
            .containsEntry("Designated Investors",         "Designated Institutional")
            .containsEntry("Borrowing Base Investors",     "Non-Rated Included")
            .containsEntry("Hurdle Investors",             "Non-Rated Included")
            .containsEntry("Excluded Investors",           "Ineligible Investors");
    }

    @Test
    void buildJson_caseInsensitiveAgentBankMatch() {
        assertThat(builder.buildJson("kkr ascendant fund")).isNotNull();
    }

    @Test
    void buildJson_fallsBackToForcedFundTemplateForGroupMappings() throws Exception {
        String json = builder.buildJson("Goldman Sachs Bank USA", "KKR Ascendant Fund");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        assertThat(config)
            .hasSize(6)
            .containsEntry("Rated Included Investors",     "Rated Included")
            .containsEntry("Non-Rated Included Investors", "Non-Rated Included")
            .containsEntry("Designated Investors",         "Designated Institutional")
            .containsEntry("Borrowing Base Investors",     "Non-Rated Included")
            .containsEntry("Hurdle Investors",             "Non-Rated Included")
            .containsEntry("Excluded Investors",           "Ineligible Investors");
    }

    @Test
    void buildJson_forcedFundTemplateOverridesAgentBankGroupingTemplate() throws Exception {
        String json = builder.buildJson("Wells Fargo (Blue Owl GP Stakes V)", "KKR Ascendant Fund");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        assertThat(config)
            .hasSize(6)
            .containsEntry("Rated Included Investors",     "Rated Included")
            .containsEntry("Non-Rated Included Investors", "Non-Rated Included")
            .containsEntry("Excluded Investors",           "Ineligible Investors");
    }

    @Test
    void buildJson_returnsNull_whenNoTemplateForBank() {
        assertThat(builder.buildJson("Unknown Bank")).isNull();
    }

    @Test
    void buildJson_returnsNull_whenTemplateHasNoGroupHeaders() {
        // Audax Fund VII not yet seeded — returns null (same expected behaviour once seeded: no group rows).
        assertThat(builder.buildJson("Silicon Valley Bank (Audax Fund VII)")).isNull();
    }

    @Test
    void buildJson_returnsNull_whenAgentBankBlank() {
        assertThat(builder.buildJson("")).isNull();
        assertThat(builder.buildJson(null)).isNull();
    }
}
