package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.FmCanonicalField;
import com.ubs.pesubapi.repository.FmAliasRepository;
import com.ubs.pesubapi.repository.FmCanonicalFieldRepository;
import com.ubs.pesubapi.repository.FmSuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SuppressWarnings("null")
class FieldMappingControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired FmCanonicalFieldRepository canonicalFieldRepo;
    @Autowired FmAliasRepository aliasRepo;
    @Autowired FmSuggestionRepository suggestionRepo;

    @BeforeEach
    void clean() {
        aliasRepo.deleteAll();
        suggestionRepo.deleteAll();
        canonicalFieldRepo.deleteAll();
    }

    private FmCanonicalField saveCanonicalField(String canonical, String group, String extractionKey) {
        FmCanonicalField f = new FmCanonicalField();
        f.setCanonical(canonical);
        f.setGroupName(group);
        f.setGroupSort(1);
        f.setFieldSort(1);
        f.setLpMasterField(canonical);
        f.setExtractionKey(extractionKey);
        return canonicalFieldRepo.save(f);
    }

    @Test
    void createAlias_roundTripThroughApi() throws Exception {
        FmCanonicalField field = saveCanonicalField("AUM", "Financial Scale", "AUM");

        mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "Assets Under Management", "tier": "Bank"}
                    """.formatted(field.getId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.text").value("Assets Under Management"))
            .andExpect(jsonPath("$.tier").value("Bank"))
            .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(get("/api/field-mapping/alias-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].group").value("Financial Scale"))
            .andExpect(jsonPath("$[0].fields[0].canonical").value("AUM"))
            .andExpect(jsonPath("$[0].fields[0].aliases[0].text").value("Assets Under Management"));
    }

    @Test
    void createAlias_duplicate_returns409() throws Exception {
        FmCanonicalField field = saveCanonicalField("NAV", "Financial Scale", "NAV");

        String body = """
            {"canonicalFieldId": %d, "text": "Net Asset Value", "tier": "Bank"}
            """.formatted(field.getId());

        mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
    }

    @Test
    void deleteAlias_removesIt() throws Exception {
        FmCanonicalField field = saveCanonicalField("UNCALLED", "Uncalled Data", "UNCALLED");

        String createResp = mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "Uncalled Capital", "tier": "Bank"}
                    """.formatted(field.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        int aliasId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        mvc.perform(delete("/api/field-mapping/aliases/{id}", aliasId))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/field-mapping/alias-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].fields[0].aliases", hasSize(0)));
    }

    @Test
    void blocklist_returnsDto_notEntity() throws Exception {
        mvc.perform(get("/api/field-mapping/blocklist"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void submitSuggestion_roundTripThroughApi() throws Exception {
        mvc.perform(post("/api/field-mapping/suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"extractedHeader": "Total AUM", "canonicalField": "AUM", "suggestedBy": "analyst@ubs.com"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.extractedHeader").value("Total AUM"))
            .andExpect(jsonPath("$.canonicalField").value("AUM"))
            .andExpect(jsonPath("$.suggestedBy").value("analyst@ubs.com"))
            .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(get("/api/field-mapping/suggestions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].extractedHeader").value("Total AUM"))
            .andExpect(jsonPath("$[0].canonicalField").value("AUM"));
    }
}
