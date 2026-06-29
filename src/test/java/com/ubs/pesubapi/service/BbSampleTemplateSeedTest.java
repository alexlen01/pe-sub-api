package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.BbTemplate;
import com.ubs.pesubapi.entity.BbTemplateTab;
import com.ubs.pesubapi.entity.BbTemplateTab.TabRole;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.BbTemplateTabRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V1_6 sampled Agent BB template profiles (BB_Templates.xlsx) — the
 * fund-keyed templates, their verbatim group-header sections, and the stacked-header
 * span — resolve through the real config builder and DB.
 */
class BbSampleTemplateSeedTest extends IntegrationTestBase {

    @Autowired ClassificationConfigBuilder  builder;
    @Autowired BbTemplateRepository         templateRepo;
    @Autowired BbTemplateTabRepository      tabRepo;
    @Autowired ObjectMapper                 mapper;

    @Test
    void kkrAscendant_resolvesItsSixVerbatimSections() throws Exception {
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
    void flatTemplates_haveNoGroupSections() {
        // Audax VII and CP VII are flat lists (has_grouping_rows = FALSE).
        assertThat(builder.buildJson("Silicon Valley Bank (Audax Fund VII)")).isNull();
        assertThat(builder.buildJson("Silicon Valley Bank (CP VII)")).isNull();
    }

    @Test
    void singleHeaderTemplates_defaultToSpanOne() {
        BbTemplate kkr = templateRepo.findAllByTemplateNameIgnoreCase("KKR Ascendant Fund").getFirst();
        BbTemplateTab grid = tabRepo.findByTemplateIdAndTabRole(kkr.getId(), TabRole.LP_GRID).orElseThrow();
        assertThat(grid.getHeaderRowSpan()).isEqualTo(1);
        assertThat(grid.getHeaderRowIndex()).isEqualTo(9);   // 0-based: Excel row 10
    }

    @Disabled("Pending V1_20 CCP VII seed migration")
    @Test
    void comvestCcpVii_resolvesItsFeederSections() throws Exception {
        Map<String, String> config = mapper.readValue(
            builder.buildJson("Silicon Valley Bank (CCP VII Lev M & M)"), new TypeReference<>() {});
        assertThat(config)
            .hasSize(5)
            .containsEntry("Levered (Delaware) Feeder", "Levered (Delaware) Feeder")
            .containsEntry("Lux Non-Treaty Feeder", "Lux Non-Treaty Feeder");
    }

    @Disabled("Pending V1_21 Carlyle CP VII seed migration")
    @Test
    void carlyleCpVii_lpGridTab_carriesStackedHeaderSpan() {
        BbTemplate cp = templateRepo.findAllByTemplateNameIgnoreCase("Silicon Valley Bank (CP VII)").getFirst();
        BbTemplateTab grid = tabRepo.findByTemplateIdAndTabRole(cp.getId(), TabRole.LP_GRID).orElseThrow();

        assertThat(grid.getHeaderRowIndex()).isEqualTo(83);  // 0-based: Excel row 84
        assertThat(grid.getHeaderRowSpan()).isEqualTo(2);    // header stacked across rows 84-85
        assertThat(grid.getSheetName()).isEqualTo("BB");
    }

    @Disabled("Pending V1_17 GS Blue Owl seed migration")
    @Test
    void blueOwlWfTemplate_hasCorrectSheetAndHeaderRow() {
        BbTemplate template = templateRepo.findAllByTemplateNameIgnoreCase("Wells Fargo (Blue Owl GP Stakes V)").getFirst();
        BbTemplateTab grid = tabRepo.findByTemplateIdAndTabRole(template.getId(), TabRole.LP_GRID).orElseThrow();
        assertThat(grid.getSheetName()).isEqualTo("Agent BB");
        assertThat(grid.getHeaderRowIndex()).isEqualTo(17);  // 0-based: Excel row 18
        assertThat(grid.getHeaderRowSpan()).isEqualTo(1);
    }

    @Disabled("Pending V1_17 GS Blue Owl seed migration")
    @Test
    void blueOwlWfTemplate_hasFourLetterPrefixedGroupingSections() throws Exception {
        String json = builder.buildJson("Wells Fargo (Blue Owl GP Stakes V)");
        assertThat(json).isNotNull();

        Map<String, String> config = mapper.readValue(json, new TypeReference<>() {});
        assertThat(config)
            .hasSize(4)
            .containsEntry("A. Rated Investors",    "Rated Included")
            .containsEntry("B. Unrated Investors",  "Non-Rated Included")
            .containsEntry("C. Eligible Investors", "Designated Institutional")
            .containsEntry("D. Excluded Investors", "Excluded");
    }
}
