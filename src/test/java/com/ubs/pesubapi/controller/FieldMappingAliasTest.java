package com.ubs.pesubapi.controller;

import com.jayway.jsonpath.JsonPath;
import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.FmAlias;
import com.ubs.pesubapi.entity.FmCanonicalField;
import com.ubs.pesubapi.repository.FmAliasRepository;
import com.ubs.pesubapi.repository.FmCanonicalFieldRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FieldMappingAliasTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired FmAliasRepository aliasRepo;
    @Autowired FmCanonicalFieldRepository canonicalFieldRepo;

    private int investorFieldId;
    private int classFieldId;

    @BeforeEach
    void setup() {
        aliasRepo.deleteAll();
        canonicalFieldRepo.deleteAll();

        investorFieldId = createField("Investor Name", "Identity & Classification", 1, 1).getId();
        classFieldId    = createField("LP Classification", "Identity & Classification", 1, 2).getId();

        // Seed one known alias on investorField so duplicate checks have something to detect
        FmAlias seed = new FmAlias();
        seed.setCanonicalFieldId(investorFieldId);
        seed.setAliasSort(1);
        seed.setAliasText("LP Name");
        seed.setTier("Core");
        aliasRepo.save(seed);
    }

    @AfterEach
    void teardown() {
        aliasRepo.deleteAll();
        canonicalFieldRepo.deleteAll();
    }

    private FmCanonicalField createField(String canonical, String group, int groupSort, int fieldSort) {
        FmCanonicalField f = new FmCanonicalField();
        f.setCanonical(canonical);
        f.setGroupName(group);
        f.setGroupSort(groupSort);
        f.setFieldSort(fieldSort);
        f.setLpMasterField(group + " - " + canonical);
        return canonicalFieldRepo.save(f);
    }

    // ── create: happy path ────────────────────────────────────────────────────

    @Test
    void createAlias_uniqueText_returns201WithAliasBody() throws Exception {
        mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "Investor", "tier": "Bank"}
                    """.formatted(investorFieldId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.text").value("Investor"))
            .andExpect(jsonPath("$.tier").value("Bank"))
            .andExpect(jsonPath("$.id").isNumber());
    }

    // ── create: exact-match duplicate across different fields ─────────────────

    @Test
    void createAlias_textExistsOnAnotherField_returns409NamingConflict() throws Exception {
        // "LP Name" is seeded on Investor Name; try to add it to LP Classification
        mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "LP Name", "tier": "Bank"}
                    """.formatted(classFieldId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("Investor Name")));
    }

    // ── create: case-insensitive duplicate across different fields ────────────

    @Test
    void createAlias_textDiffersOnlyCaseFromExisting_returns409() throws Exception {
        // "lp name" differs only in case from seeded "LP Name" on Investor Name
        mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "lp name", "tier": "Bank"}
                    """.formatted(classFieldId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("Investor Name")));
    }

    // ── update: rename to a text owned by a different field ───────────────────

    @Test
    void updateAlias_renameToDuplicateText_returns409() throws Exception {
        // Create a bank alias on LP Classification
        String created = mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "Classification Type", "tier": "Bank"}
                    """.formatted(classFieldId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        int aliasId = (int) JsonPath.read(created, "$.id");

        // Rename to "LP Name" which belongs to Investor Name → must be rejected
        mvc.perform(patch("/api/field-mapping/aliases/" + aliasId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"text": "LP Name"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("Investor Name")));
    }

    // ── update: same text different case on the same alias → allowed ──────────

    @Test
    void updateAlias_sameTextDifferentCase_returns200() throws Exception {
        // Create a bank alias, then update it to same text in different case
        String created = mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "Classification Type", "tier": "Bank"}
                    """.formatted(classFieldId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        int aliasId = (int) JsonPath.read(created, "$.id");

        mvc.perform(patch("/api/field-mapping/aliases/" + aliasId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"text": "classification type"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("classification type"));
    }

    // ── update: changed text is reflected in alias-groups GET ─────────────────

    @Test
    void updateAlias_roundTrip_updatedTextAppearsInAliasGroupsResponse() throws Exception {
        String created = mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "Category", "tier": "Bank"}
                    """.formatted(classFieldId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        int aliasId = (int) JsonPath.read(created, "$.id");

        mvc.perform(patch("/api/field-mapping/aliases/" + aliasId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"text": "Investor Category"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("Investor Category"));

        mvc.perform(get("/api/field-mapping/alias-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$[0].fields[?(@.canonical == 'LP Classification')].aliases[?(@.id == " + aliasId + ")].text",
                org.hamcrest.Matchers.hasItem("Investor Category")
            ));
    }
}
