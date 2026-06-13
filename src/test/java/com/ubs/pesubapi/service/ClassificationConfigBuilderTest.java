package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads against the seeded BB template registry (V1_2 + V1_6 migrations). Goldman Sachs
 * is seeded with an LP_GRID tab and group headers mapped to Agent LP Classification
 * values; SVB and Wells Fargo have an LP_GRID tab but no group headers.
 */
class ClassificationConfigBuilderTest extends IntegrationTestBase {

    @Autowired ClassificationConfigBuilder builder;
    @Autowired ObjectMapper                mapper;

    @Test
    void buildJson_mapsSeededGroupHeadersToAgentClassification() throws Exception {
        String json = builder.buildJson("Goldman Sachs Bank USA");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        // V1_6 maps the literal GS group-header text to the Agent LP Classification taxonomy.
        // header_text stays as the agent document's wording; only the classification changes.
        assertThat(config)
            .containsEntry("Rated Investors", "Rated Included")
            .containsEntry("Unrated Investors", "Non-Rated Included")
            .containsEntry("Eligible Investors", "Designated Institutional")
            .containsEntry("Excluded Investors", "Excluded");
    }

    @Test
    void buildJson_caseInsensitiveAgentBankMatch() {
        assertThat(builder.buildJson("goldman sachs bank usa")).isNotNull();
    }

    @Test
    void buildJson_returnsNull_whenNoTemplateForBank() {
        assertThat(builder.buildJson("Unknown Bank")).isNull();
    }

    @Test
    void buildJson_returnsNull_whenTemplateHasNoGroupHeaders() {
        // SVB has an LP_GRID tab seeded but no group-header rows.
        assertThat(builder.buildJson("Silicon Valley Bank")).isNull();
    }

    @Test
    void buildJson_returnsNull_whenAgentBankBlank() {
        assertThat(builder.buildJson("")).isNull();
        assertThat(builder.buildJson(null)).isNull();
    }
}
