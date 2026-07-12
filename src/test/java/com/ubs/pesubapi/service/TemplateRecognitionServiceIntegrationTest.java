package com.ubs.pesubapi.service;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.dto.ResolvedTemplate;
import com.ubs.pesubapi.dto.WorkbookSignals;
import com.ubs.pesubapi.entity.BbTemplate;
import com.ubs.pesubapi.entity.BbTemplateTab;
import com.ubs.pesubapi.entity.BbTemplateTab.TabRole;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.BbTemplateTabRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRecognitionServiceIntegrationTest extends IntegrationTestBase {

    @Autowired TemplateRecognitionService recognitionService;
    @Autowired BbTemplateRepository templateRepo;
    @Autowired BbTemplateTabRepository tabRepo;

    private BbTemplate template;

    // bb_templates is Flyway-seeded reference data preserved across tests, so remove
    // the template this test registered to keep recognition scoring deterministic.
    @AfterEach
    void removeRegisteredTemplate() {
        if (template != null) {
            tabRepo.deleteAll(tabRepo.findByTemplateIdAndTabRoleOrderByTabSortAsc(template.getId(), TabRole.LP_GRID));
            templateRepo.delete(template);
            template = null;
        }
    }

    @Test
    void multiTabTemplate_withSingleDistinctSheetName_resolvesAsSingleTab() {
        template = registerTemplate("dup-tab-fund");
        tabRepo.save(tab(template, 1, "Agent BB"));
        tabRepo.save(tab(template, 2, "Agent BB"));

        ResolvedTemplate resolved = recognitionService.recognize(
            signals("Agent BB"), "Test Agent", "dup-tab-fund");

        // Both declared tabs point at the same physical sheet: resolving them as
        // multi-tab would extract every LP twice, so the template collapses to single-tab.
        assertThat(resolved.recognized()).isTrue();
        assertThat(resolved.multiTab()).isFalse();
        assertThat(resolved.sheetNames()).isEmpty();
        assertThat(resolved.sheetName()).isEqualTo("Agent BB");
    }

    @Test
    void multiTabTemplate_withDistinctSheetNames_staysMultiTab() {
        template = registerTemplate("two-sleeve-fund");
        tabRepo.save(tab(template, 1, "BB - Onshore"));
        tabRepo.save(tab(template, 2, "BB - Offshore"));

        ResolvedTemplate resolved = recognitionService.recognize(
            signals("BB - Onshore"), "Test Agent", "two-sleeve-fund");

        assertThat(resolved.recognized()).isTrue();
        assertThat(resolved.multiTab()).isTrue();
        assertThat(resolved.sheetNames()).containsExactly("BB - Onshore", "BB - Offshore");
    }

    private BbTemplate registerTemplate(String slug) {
        BbTemplate t = new BbTemplate();
        t.setTemplateName(slug); // TEST ONLY
        t.setTemplateSlug(slug);
        t.setAgentName("Test Agent");
        t.setSheetName("Agent BB");
        t.setHeaderRowIndex(17);
        t.setTrancheCount(2);
        t.setAutoLearned(false);
        return templateRepo.save(t);
    }

    private BbTemplateTab tab(BbTemplate t, int sort, String sheetName) {
        BbTemplateTab tab = new BbTemplateTab();
        tab.setTemplate(t);
        tab.setTabRole(TabRole.LP_GRID);
        tab.setTabSort(sort);
        tab.setSheetName(sheetName);
        tab.setHeaderRowIndex(17);
        return tab;
    }

    private WorkbookSignals signals(String sheetName) {
        return new WorkbookSignals("workbook.xlsx",
            List.of(new WorkbookSignals.SheetSignals(sheetName, List.of())));
    }
}
