package com.ubs.pesubapi.controller;

import com.jayway.jsonpath.JsonPath;
import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.repository.BbTemplateGroupRepository;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.BbTemplateTabRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("null")
class BbTemplateControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired BbTemplateRepository templateRepo;
    @Autowired BbTemplateTabRepository tabRepo;
    @Autowired BbTemplateGroupRepository groupRepo;

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
