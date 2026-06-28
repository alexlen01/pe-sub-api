package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads against the seeded BB template registry (V1_11 migration).
 * Wells Fargo (Blue Owl GP Stakes V) (Class A) carries group-header classification rows;
 * Silicon Valley Bank (Audax Fund VII) (Class B) has an LP_GRID tab but no group headers.
 */
class ClassificationConfigBuilderTest extends IntegrationTestBase {

    @Autowired ClassificationConfigBuilder builder;
    @Autowired ObjectMapper                mapper;

    @Test
    void buildJson_mapsSeededGroupHeadersToAgentClassification() throws Exception {
        String json = builder.buildJson("Wells Fargo (Blue Owl GP Stakes V)");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        assertThat(config)
            .containsEntry("A. Rated Investors",    "Rated Included")
            .containsEntry("B. Unrated Investors",  "Non-Rated Included")
            .containsEntry("C. Eligible Investors", "Designated Institutional")
            .containsEntry("D. Excluded Investors", "Excluded");
    }

    @Test
    void buildJson_caseInsensitiveAgentBankMatch() {
        assertThat(builder.buildJson("wells fargo (blue owl gp stakes v)")).isNotNull();
    }

    @Test
    void buildJson_fallsBackToForcedFundTemplateForGroupMappings() throws Exception {
        String json = builder.buildJson("Goldman Sachs Bank USA", "Petershill IV");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        assertThat(config)
            .hasSize(5)
            .containsEntry("Included Investors (Rated)", "Rated Included")
            .containsEntry("Inlcuded Investors (Non-Rated)", "Non-Rated Included")
            .containsEntry("Institutional Designated Investors", "Designated Institutional")
            .containsEntry("PWM Designated Investors", "Designated PWM")
            .containsEntry("Excluded Investors", "Ineligible Investors");
    }

    @Test
    void buildJson_forcedFundTemplateOverridesAgentBankGroupingTemplate() throws Exception {
        String json = builder.buildJson("Wells Fargo (Blue Owl GP Stakes V)", "Petershill IV");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        assertThat(config)
            .hasSize(5)
            .containsEntry("Included Investors (Rated)", "Rated Included")
            .containsEntry("Inlcuded Investors (Non-Rated)", "Non-Rated Included")
            .containsEntry("Excluded Investors", "Ineligible Investors");
    }

    @Test
    void buildJson_returnsNull_whenNoTemplateForBank() {
        assertThat(builder.buildJson("Unknown Bank")).isNull();
    }

    @Test
    void buildJson_returnsNull_whenTemplateHasNoGroupHeaders() {
        // Silicon Valley Bank (Audax Fund VII) has an LP_GRID tab seeded but no group-header rows.
        assertThat(builder.buildJson("Silicon Valley Bank (Audax Fund VII)")).isNull();
    }

    @Test
    void buildJson_returnsNull_whenAgentBankBlank() {
        assertThat(builder.buildJson("")).isNull();
        assertThat(builder.buildJson(null)).isNull();
    }
}
