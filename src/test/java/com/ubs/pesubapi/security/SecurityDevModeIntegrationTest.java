package com.ubs.pesubapi.security;

import com.ubs.pesubapi.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Default (dev) security mode: every request is authenticated as the configured dev analyst, so
 * the header-less UI and the existing suite work unchanged. Role boundaries are still enforced —
 * the service-only ingest endpoint rejects an operator role.
 */
class SecurityDevModeIntegrationTest extends IntegrationTestBase {

    private static final String INGEST_BODY = """
        {"facilityId":1,"extraction":{"template":{"format":"UNKNOWN","version":null,"headerRowIndex":0},"records":[],"totalFlagged":0}}
        """;

    @Autowired MockMvc mvc;

    @Test
    void protectedEndpoint_isAuthenticatedByDefault() throws Exception {
        mvc.perform(get("/api/facilities")).andExpect(status().isOk());
    }

    @Test
    void publicPing_isReachableWithoutRole() throws Exception {
        mvc.perform(get("/api/ping")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "analyst", roles = {"ANALYST"})
    void ingest_asAnalyst_isForbidden() throws Exception {
        mvc.perform(post("/api/lpRecords/ingest").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "extraction-svc", roles = {"SERVICE"})
    void ingest_asServiceRole_passesAuthorization() throws Exception {
        mvc.perform(post("/api/lpRecords/ingest").contentType(MediaType.APPLICATION_JSON).content(INGEST_BODY))
            .andExpect(status().isOk());
    }

    @Test
    void devMode_honorsSwitcherHeadersWhenPresent() throws Exception {
        // The dev role switcher sends X-Auth-* headers; DEV mode must honor them (not fall back to
        // the fixed ANALYST dev identity) so a selected VIEWER is correctly denied a write.
        mvc.perform(post("/api/facilities")
                .header("X-Auth-User", "lt09341")
                .header("X-Auth-Roles", "VIEWER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"agentBank\":\"Y\"}"))
            .andExpect(status().isForbidden());
    }
}
