package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.BbTemplate;
import com.ubs.pesubapi.entity.BbTemplateTab;
import com.ubs.pesubapi.entity.BbTemplateTab.TabRole;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.BbTemplateTabRepository;
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
        String json = builder.buildJson("KKR Ascendant Fund / JP Morgan");
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
    void comvestCcpVii_resolvesItsFeederSections() throws Exception {
        Map<String, String> config = mapper.readValue(
            builder.buildJson("CCP VII Lev M & M / Silicon Valley Bank"), new TypeReference<>() {});
        assertThat(config)
            .hasSize(5)
            .containsEntry("Levered (Delaware) Feeder", "Levered (Delaware) Feeder")
            .containsEntry("Lux Non-Treaty Feeder", "Lux Non-Treaty Feeder");
    }

    @Test
    void flatTemplates_haveNoGroupSections() {
        // Audax VII and CP VII are flat lists (has_grouping_rows = FALSE).
        assertThat(builder.buildJson("Audax Fund VII / Silicon Valley Bank")).isNull();
        assertThat(builder.buildJson("CP VII / Silicon Valley Bank")).isNull();
    }

    @Test
    void carlyleCpVii_lpGridTab_carriesStackedHeaderSpan() {
        BbTemplate cp = templateRepo.findAllByTemplateNameIgnoreCase("CP VII / Silicon Valley Bank").getFirst();
        BbTemplateTab grid = tabRepo.findByTemplateIdAndTabRole(cp.getId(), TabRole.LP_GRID).orElseThrow();

        assertThat(grid.getHeaderRowIndex()).isEqualTo(83);  // 0-based: Excel row 84
        assertThat(grid.getHeaderRowSpan()).isEqualTo(2);    // header stacked across rows 84-85
        assertThat(grid.getSheetName()).isEqualTo("BB");
    }

    @Test
    void singleHeaderTemplates_defaultToSpanOne() {
        BbTemplate kkr = templateRepo.findAllByTemplateNameIgnoreCase("KKR Ascendant Fund / JP Morgan").getFirst();
        BbTemplateTab grid = tabRepo.findByTemplateIdAndTabRole(kkr.getId(), TabRole.LP_GRID).orElseThrow();
        assertThat(grid.getHeaderRowSpan()).isEqualTo(1);
        assertThat(grid.getHeaderRowIndex()).isEqualTo(9);   // 0-based: Excel row 10
    }

    @Test
    void blueOwlWfTemplate_hasCorrectSheetAndHeaderRow() {
        BbTemplate template = templateRepo.findAllByTemplateNameIgnoreCase("Blue Owl GP Stakes V / Wells Fargo").getFirst();
        BbTemplateTab grid = tabRepo.findByTemplateIdAndTabRole(template.getId(), TabRole.LP_GRID).orElseThrow();
        assertThat(grid.getSheetName()).isEqualTo("Agent BB");
        assertThat(grid.getHeaderRowIndex()).isEqualTo(17);  // 0-based: Excel row 18
        assertThat(grid.getHeaderRowSpan()).isEqualTo(1);
    }

    @Test
    void blueOwlWfTemplate_hasFourLetterPrefixedGroupingSections() throws Exception {
        String json = builder.buildJson("Blue Owl GP Stakes V / Wells Fargo");
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
