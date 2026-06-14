package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads against the seeded BB template registry (V1_2 + V1_5 migrations).
 * Wells Fargo Class A is seeded with an LP_GRID tab and group headers mapped to
 * Agent LP Classification values; Wells Fargo Class B and SVB have no group headers.
 */
class ClassificationConfigBuilderTest extends IntegrationTestBase {

    @Autowired ClassificationConfigBuilder builder;
    @Autowired ObjectMapper                mapper;

    @Test
    void buildJson_mapsSeededGroupHeadersToAgentClassification() throws Exception {
        // Wells Fargo Class A carries the group-header classification rows (formerly the
        // Goldman Sachs prior-agent template for Blue Owl GP Stakes V, renamed in V1_5).
        String json = builder.buildJson("Wells Fargo");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        assertThat(config)
            .containsEntry("Rated Investors", "Rated Included")
            .containsEntry("Unrated Investors", "Non-Rated Included")
            .containsEntry("Eligible Investors", "Designated Institutional")
            .containsEntry("Excluded Investors", "Excluded");
    }

    @Test
    void buildJson_caseInsensitiveAgentBankMatch() {
        assertThat(builder.buildJson("wells fargo")).isNotNull();
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
