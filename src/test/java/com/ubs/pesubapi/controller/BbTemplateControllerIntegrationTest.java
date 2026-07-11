package com.ubs.pesubapi.controller;

import com.jayway.jsonpath.JsonPath;
import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.repository.BbTemplateGroupRepository;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.BbTemplateTabRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.io.InputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BbTemplateControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired BbTemplateRepository templateRepo;
    @Autowired BbTemplateTabRepository tabRepo;
    @Autowired BbTemplateGroupRepository groupRepo;

    private MockMultipartFile templateWorkbook(String fileName) throws Exception {
        Path path = Path.of("src/test/resources/templates", fileName);
        try (InputStream input = Files.newInputStream(path)) {
            return new MockMultipartFile(
                "file",
                fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                input);
        }
    }

    @Test
    void importTemplate_groupedWorkbookCreatesTemplateAndVersionsDuplicateSlug() throws Exception {
        MockMultipartFile file = templateWorkbook("BB-Template-Import-grouped.xlsx");

        mvc.perform(multipart("/api/bb-templates/import").file(file))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateSlug").value("grouped-sample"))
            .andExpect(jsonPath("$.templateName").value("grouped-sample"))
            .andExpect(jsonPath("$.agentName").value("Northbank Agent"))
            .andExpect(jsonPath("$.tabs[0].headerRowIndex").value(10))
            .andExpect(jsonPath("$.tabs[0].groups.length()").value(5))
            .andExpect(jsonPath("$.tabs[0].groups[1].headerText").value("Included Investors (Non-Rated)"));

        MockMultipartFile duplicate = templateWorkbook("BB-Template-Import-grouped.xlsx");

        mvc.perform(multipart("/api/bb-templates/import").file(duplicate))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateSlug").value("grouped-sample-1"))
            .andExpect(jsonPath("$.templateName").value("grouped-sample-1"));
    }

    @Test
    void importTemplate_upsertModeReplacesExistingSlugWithoutVersioning() throws Exception {
        String created = mvc.perform(multipart("/api/bb-templates/import")
                .file(templateWorkbook("BB-Template-Import-upsert.xlsx"))
                .param("mode", "upsert"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateSlug").value("upsert-sample"))
            .andReturn().getResponse().getContentAsString();

        int templateId = JsonPath.read(created, "$.id");

        mvc.perform(multipart("/api/bb-templates/import")
                .file(templateWorkbook("BB-Template-Import-upsert.xlsx"))
                .param("mode", "upsert"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(templateId))
            .andExpect(jsonPath("$.templateSlug").value("upsert-sample"))
            .andExpect(jsonPath("$.templateName").value("upsert-sample"));

        assertThat(templateRepo.findByTemplateSlug("upsert-sample")).isPresent();
        assertThat(templateRepo.findByTemplateSlug("upsert-sample-1")).isEmpty();
    }

    @Test
    void importTemplate_multiTabSamplesPreserveSleeveTabSemantics() throws Exception {
        mvc.perform(multipart("/api/bb-templates/import")
                .file(templateWorkbook("BB-Template-Import-autodiscover-single.xlsx")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateSlug").value("autodiscover-single"))
            .andExpect(jsonPath("$.autoDiscoverTabs").value(true))
            .andExpect(jsonPath("$.tabs.length()").value(1))
            .andExpect(jsonPath("$.tabs[0].sheetName").isEmpty())
            .andExpect(jsonPath("$.tabs[0].headerRowIndex").value(12))
            .andExpect(jsonPath("$.tabs[0].groups.length()").value(0));

        mvc.perform(multipart("/api/bb-templates/import")
                .file(templateWorkbook("BB-Template-Import-autodiscover-nogroups.xlsx")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateSlug").value("autodiscover-nogroups"))
            .andExpect(jsonPath("$.autoDiscoverTabs").value(true))
            .andExpect(jsonPath("$.hasGroupingRows").value(false))
            .andExpect(jsonPath("$.tabs[0].groups.length()").value(0));

        mvc.perform(multipart("/api/bb-templates/import")
                .file(templateWorkbook("BB-Template-Import-two-tab.xlsx")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateSlug").value("two-tab-sample"))
            .andExpect(jsonPath("$.autoDiscoverTabs").value(false))
            .andExpect(jsonPath("$.tabs.length()").value(2))
            .andExpect(jsonPath("$.tabs[0].sheetName").value("BB - Onshore"))
            .andExpect(jsonPath("$.tabs[1].sheetName").value("BB - Offshore"))
            .andExpect(jsonPath("$.tabs[0].headerRowIndex").value(83))
            .andExpect(jsonPath("$.tabs[0].headerRowSpan").value(2));

        mvc.perform(multipart("/api/bb-templates/import")
                .file(templateWorkbook("BB-Template-Import-single-group.xlsx")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateSlug").value("single-group-sample"))
            .andExpect(jsonPath("$.headerRowIndex").value(9))
            .andExpect(jsonPath("$.tabs[0].headerRowIndex").value(9))
            .andExpect(jsonPath("$.tabs[0].groups.length()").value(4));
    }

    @Test
    void deleteTemplate_removesTemplateTabsAndGroups() throws Exception {
        String created = mvc.perform(post("/api/bb-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "templateName": "Delete Test Template",
                      "templateClass": "A",
                      "sheetName": "BB",
                      "headerRowIndex": 4,
                      "autoLearned": false,
                      "trancheCount": 1,
                      "hasGroupingRows": true,
                      "hasColorFlags": false,
                      "summaryRowsAboveHeader": 4,
                      "tabs": [{
                        "tabRole": "LP_GRID",
                        "tabSort": 1,
                        "sheetName": "BB",
                        "headerRowIndex": 4,
                        "headerRowSpan": 1,
                        "skipRowKeywords": [],
                        "groups": [{
                          "groupSort": 1,
                          "headerText": "Included Investors",
                          "classification": "Rated Included"
                        }]
                      }]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateName").value("Delete Test Template"))
            .andReturn().getResponse().getContentAsString();

        int templateId = JsonPath.read(created, "$.id");
        int tabId = JsonPath.read(created, "$.tabs[0].id");

        mvc.perform(delete("/api/bb-templates/{id}", templateId))
            .andExpect(status().isNoContent());

        assertThat(templateRepo.existsById(templateId)).isFalse();
        assertThat(tabRepo.findByTemplateIdOrderByTabSortAsc(templateId)).isEmpty();
        assertThat(groupRepo.findByTabIdOrderByGroupSortAsc(tabId)).isEmpty();

        mvc.perform(get("/api/bb-templates/{id}", templateId))
            .andExpect(status().isNotFound());
    }
}
