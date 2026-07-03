package com.ubs.pesubapi.security;

import com.ubs.pesubapi.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production (gateway) security mode: identity comes only from the SSO reverse-proxy headers.
 * A request without the user header is rejected 401; the public liveness endpoint stays open.
 */
@TestPropertySource(properties = "app.security.mode=gateway")
class SecurityGatewayModeIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void protectedEndpoint_withoutIdentityHeader_isUnauthorized() throws Exception {
        mvc.perform(get("/api/facilities")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withGatewayIdentityHeaders_isAuthorized() throws Exception {
        mvc.perform(get("/api/facilities")
                .header("X-Auth-User", "jane.analyst@ubs.com")
                .header("X-Auth-Roles", "ANALYST"))
            .andExpect(status().isOk());
    }

    @Test
    void publicPing_isReachableWithoutIdentityHeader() throws Exception {
        mvc.perform(get("/api/ping")).andExpect(status().isOk());
    }
}
