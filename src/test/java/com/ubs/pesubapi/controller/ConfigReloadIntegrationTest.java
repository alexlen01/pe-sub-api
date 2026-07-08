package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The config cache is loaded once at startup, so feeds that write the shared config
 * table directly (pe-sub-jobs cls-conc-limits-ingest) must be able to surface their
 * values via POST /api/config/reload without an application restart.
 */
class ConfigReloadIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc      mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void restoreSeededValue() throws Exception {
        // Put the seeded Rated Investor default back and re-sync the cache for later tests.
        jdbc.update("""
                UPDATE config
                SET value = jsonb_set(value, '{"Rated Investor"}', '20.0'::jsonb)
                WHERE key = 'cls_conc_limit_defaults'
                """);
        mvc.perform(post("/api/config/reload")).andExpect(status().isNoContent());
    }

    @Test
    void reloadSurfacesDirectDatabaseWrites() throws Exception {
        // Simulate the pe-sub-jobs feed: a direct DB write, bypassing the API. TEST ONLY
        jdbc.update("""
                UPDATE config
                SET value = jsonb_set(value, '{"Rated Investor"}', '4.25'::jsonb)
                WHERE key = 'cls_conc_limit_defaults'
                """);

        // The startup-loaded cache still serves the seeded value.
        mvc.perform(get("/api/config/eligibility"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.CLS_CONC_LIMIT_DEFAULTS['Rated Investor']").value(20.0));

        mvc.perform(post("/api/config/reload"))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/config/eligibility"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.CLS_CONC_LIMIT_DEFAULTS['Rated Investor']").value(4.25));
    }

    @Test
    void eligibilityServesSeededConcLimitBounds() throws Exception {
        // The cls_conc_limit_bounds key (accepted min/max range per class, used by the LP
        // entry form to warn on out-of-range limits) round-trips through the eligibility GET.
        mvc.perform(get("/api/config/eligibility"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.CLS_CONC_LIMIT_BOUNDS['Rated Investor'].min").value(15.0))
            .andExpect(jsonPath("$.CLS_CONC_LIMIT_BOUNDS['Rated Investor'].max").value(20.0))
            .andExpect(jsonPath("$.CLS_CONC_LIMIT_BOUNDS['Excluded'].min").value(0.0))
            .andExpect(jsonPath("$.CLS_CONC_LIMIT_BOUNDS['Excluded'].max").value(0.0));
    }
}
