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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Field-mapping endpoints: alias CRUD with global (case-insensitive) uniqueness,
 * alias-groups round-trips, blocklist DTO shape, and suggestions.
 *
 * The fm_* tables are Flyway-seeded reference data that IntegrationTestBase deliberately
 * preserves — so this class NEVER wipes them. It works against its own distinctively
 * named fixtures ("ZZ Test …") created alongside the seed and removes exactly those rows
 * in teardown. Assertions filter by id/name instead of relying on array positions.
 */
class FieldMappingIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired FmAliasRepository aliasRepo;
    @Autowired FmCanonicalFieldRepository canonicalFieldRepo;
    @Autowired JdbcTemplate jdbc;

    private int investorFieldId;
    private int classFieldId;

    @BeforeEach
    void setup() {
        investorFieldId = createField("ZZ Test Investor", 1).getId();
        classFieldId    = createField("ZZ Test Category", 2).getId();

        // Seed one known alias on the investor field so duplicate checks have something
        // to detect. "zz " prefix keeps it clear of the seeded alias dictionary, which
        // shares the same global uniqueness scope.
        FmAlias seed = new FmAlias();
        seed.setCanonicalFieldId(investorFieldId);
        seed.setAliasSort(1);
        seed.setAliasText("zz lp name");
        seed.setTier("Core");
        aliasRepo.save(seed);
    }

    @AfterEach
    void teardown() {
        jdbc.update("DELETE FROM fm_aliases WHERE canonical_field_id IN (?, ?)", investorFieldId, classFieldId);
        jdbc.update("DELETE FROM fm_canonical_fields WHERE id IN (?, ?)", investorFieldId, classFieldId);
        jdbc.update("DELETE FROM fm_suggestions WHERE extracted_header = 'ZZ Total AUM'");
    }

    private FmCanonicalField createField(String canonical, int fieldSort) {
        FmCanonicalField f = new FmCanonicalField();
        f.setCanonical(canonical);
        f.setGroupName("ZZ Test Group");
        f.setGroupSort(99);
        f.setFieldSort(fieldSort);
        f.setLpMasterField("ZZ Test Group - " + canonical);
        return canonicalFieldRepo.save(f);
    }

    // ── create: happy path + appears in alias-groups ──────────────────────────

    @Test
    void createAlias_uniqueText_returns201AndAppearsInAliasGroups() throws Exception {
        String created = mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "zz investor", "tier": "Bank"}
                    """.formatted(investorFieldId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.text").value("zz investor"))
            .andExpect(jsonPath("$.tier").value("Bank"))
            .andExpect(jsonPath("$.id").isNumber())
            .andReturn().getResponse().getContentAsString();
        int aliasId = (int) JsonPath.read(created, "$.id");

        mvc.perform(get("/api/field-mapping/alias-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$..aliases[?(@.id == " + aliasId + ")].text", hasItem("zz investor")));
    }

    // ── create: exact-match duplicate across different fields ─────────────────

    @Test
    void createAlias_textExistsOnAnotherField_returns409NamingConflict() throws Exception {
        // "zz lp name" is seeded on ZZ Test Investor; try to add it to ZZ Test Category
        mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "zz lp name", "tier": "Bank"}
                    """.formatted(classFieldId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("ZZ Test Investor")));
    }

    // ── create: case-insensitive duplicate across different fields ────────────

    @Test
    void createAlias_textDiffersOnlyCaseFromExisting_returns409() throws Exception {
        mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "ZZ LP Name", "tier": "Bank"}
                    """.formatted(classFieldId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("ZZ Test Investor")));
    }

    // ── update: rename to a text owned by a different field ───────────────────

    @Test
    void updateAlias_renameToDuplicateText_returns409() throws Exception {
        int aliasId = createAliasOnClassField("zz classification type");

        // Rename to "zz lp name" which belongs to ZZ Test Investor → must be rejected
        mvc.perform(patch("/api/field-mapping/aliases/" + aliasId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"text": "zz lp name"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("ZZ Test Investor")));
    }

    // ── update: same text different case on the same alias → allowed ──────────

    @Test
    void updateAlias_sameTextDifferentCase_returns200() throws Exception {
        int aliasId = createAliasOnClassField("zz classification type");

        mvc.perform(patch("/api/field-mapping/aliases/" + aliasId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"text": "ZZ Classification Type"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("ZZ Classification Type"));
    }

    // ── update: changed text is reflected in alias-groups GET ─────────────────

    @Test
    void updateAlias_roundTrip_updatedTextAppearsInAliasGroupsResponse() throws Exception {
        int aliasId = createAliasOnClassField("zz category");

        mvc.perform(patch("/api/field-mapping/aliases/" + aliasId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"text": "zz investor category"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("zz investor category"));

        mvc.perform(get("/api/field-mapping/alias-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$..aliases[?(@.id == " + aliasId + ")].text", hasItem("zz investor category")));
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    void deleteAlias_removesIt() throws Exception {
        int aliasId = createAliasOnClassField("zz uncalled capital");

        mvc.perform(delete("/api/field-mapping/aliases/{id}", aliasId))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/field-mapping/alias-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$..aliases[?(@.id == " + aliasId + ")]", empty()));
    }

    // ── blocklist ───────────────────────────────────────────────────────────────

    @Test
    void blocklist_returnsDto_notEntity() throws Exception {
        mvc.perform(get("/api/field-mapping/blocklist"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    // ── suggestions ─────────────────────────────────────────────────────────────

    @Test
    void submitSuggestion_roundTripThroughApi() throws Exception {
        String created = mvc.perform(post("/api/field-mapping/suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"extractedHeader": "ZZ Total AUM", "canonicalField": "AUM", "suggestedBy": "analyst@ubs.com"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.extractedHeader").value("ZZ Total AUM"))
            .andExpect(jsonPath("$.canonicalField").value("AUM"))
            .andExpect(jsonPath("$.suggestedBy").value("analyst@ubs.com"))
            .andExpect(jsonPath("$.id").isNumber())
            .andReturn().getResponse().getContentAsString();
        int suggestionId = (int) JsonPath.read(created, "$.id");

        // The suggestions table carries Flyway-seeded rows, so assert on our row by id
        // rather than on list size or position.
        mvc.perform(get("/api/field-mapping/suggestions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + suggestionId + ")].extractedHeader", hasItem("ZZ Total AUM")))
            .andExpect(jsonPath("$[?(@.id == " + suggestionId + ")].canonicalField", hasItem("AUM")));
    }

    private int createAliasOnClassField(String text) throws Exception {
        String created = mvc.perform(post("/api/field-mapping/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"canonicalFieldId": %d, "text": "%s", "tier": "Bank"}
                    """.formatted(classFieldId, text)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return (int) JsonPath.read(created, "$.id");
    }
}
