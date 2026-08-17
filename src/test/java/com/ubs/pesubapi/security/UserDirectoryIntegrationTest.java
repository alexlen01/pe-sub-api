package com.ubs.pesubapi.security;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.User;
import com.ubs.pesubapi.repository.UserRepository;
import com.ubs.pesubapi.service.UserDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The users table is a directory mirrored from the gateway's {@code X-Auth-*} headers: every
 * authenticated request upserts the caller, so a stored uuName can later be rendered as a person
 * on a screen. Gateway mode is used throughout so identity comes only from headers.
 */
@TestPropertySource(properties = "app.security.mode=gateway")
class UserDirectoryIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc              mvc;
    @Autowired UserRepository       userRepo;
    @Autowired UserDirectoryService directory;

    @BeforeEach
    void clearDirectory() {
        userRepo.deleteAll();
        // The service suppresses repeat writes for an unchanged identity; drop that memory so each
        // test's first request actually writes.
        directory.resetSyncCache();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asLeAnalyst() {
        return get("/api/facilities")
            .header("X-Auth-User", "le05751")          // TEST ONLY
            .header("X-Auth-First-Name", "Alex")
            .header("X-Auth-Last-Name", "Len")
            .header("X-Auth-Email", "alex.len@ubs.com")
            .header("X-Auth-Roles", "ANALYST");
    }

    @Test
    void authenticatedRequest_persistsGatewayIdentity() throws Exception {
        mvc.perform(asLeAnalyst()).andExpect(status().isOk());

        User stored = userRepo.findByUuName("le05751").orElseThrow();
        assertThat(stored.getFirstName()).isEqualTo("Alex");
        assertThat(stored.getLastName()).isEqualTo("Len");
        assertThat(stored.getEmail()).isEqualTo("alex.len@ubs.com");
        assertThat(stored.getRole()).isEqualTo("Analyst");
        assertThat(stored.getLastSeenAt()).isNotNull();
    }

    @Test
    void repeatRequests_doNotDuplicateTheDirectoryRow() throws Exception {
        mvc.perform(asLeAnalyst()).andExpect(status().isOk());
        mvc.perform(asLeAnalyst()).andExpect(status().isOk());
        mvc.perform(asLeAnalyst()).andExpect(status().isOk());

        assertThat(userRepo.findAll()).hasSize(1);
    }

    @Test
    void changedGatewayAttributes_refreshTheStoredRow() throws Exception {
        mvc.perform(asLeAnalyst()).andExpect(status().isOk());

        // Same person, promoted and renamed in the corporate directory.
        mvc.perform(get("/api/facilities")
                .header("X-Auth-User", "le05751")
                .header("X-Auth-First-Name", "Alex")
                .header("X-Auth-Last-Name", "Lenard")
                .header("X-Auth-Email", "alex.lenard@ubs.com")
                .header("X-Auth-Roles", "MANAGER"))
            .andExpect(status().isOk());

        User stored = userRepo.findByUuName("le05751").orElseThrow();
        assertThat(userRepo.findAll()).hasSize(1);
        assertThat(stored.getLastName()).isEqualTo("Lenard");
        assertThat(stored.getEmail()).isEqualTo("alex.lenard@ubs.com");
        assertThat(stored.getRole()).isEqualTo("Manager");
    }

    @Test
    void multipleRoles_persistTheMostPrivilegedOne() throws Exception {
        mvc.perform(get("/api/facilities")
                .header("X-Auth-User", "mg00123")       // TEST ONLY
                .header("X-Auth-Roles", "ANALYST,MANAGER"))
            .andExpect(status().isOk());

        assertThat(userRepo.findByUuName("mg00123").orElseThrow().getRole()).isEqualTo("Manager");
    }

    @Test
    void servicePrincipal_isNotAddedToTheDirectory() throws Exception {
        // An ingest job is a machine, not a person — the directory must stay human-only.
        mvc.perform(get("/api/facilities")
                .header("X-Auth-User", "pe-sub-jobs")
                .header("X-Auth-Roles", "SERVICE"))
            .andExpect(status().isOk());

        assertThat(userRepo.findByUuName("pe-sub-jobs")).isEmpty();
    }

    @Test
    void missingNameHeaders_fallBackToUuNameForDisplay() throws Exception {
        mvc.perform(get("/api/facilities")
                .header("X-Auth-User", "vw09999")       // TEST ONLY
                .header("X-Auth-Roles", "VIEWER"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/users/{uuName}", "vw09999")
                .header("X-Auth-User", "vw09999")
                .header("X-Auth-Roles", "VIEWER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("vw09999"))
            .andExpect(jsonPath("$.role").value("Viewer"))
            .andExpect(jsonPath("$.firstName").value(""));
    }

    // ── Retrieval surface ──────────────────────────────────────────────────────────────

    @Test
    void requestWithoutNameHeaders_keepsThePreviouslyKnownName() throws Exception {
        mvc.perform(asLeAnalyst()).andExpect(status().isOk());

        // A request carrying no name headers means "not supplied", not "now empty" — blanking a
        // known display name here would degrade every screen that renders this person.
        mvc.perform(get("/api/facilities")
                .header("X-Auth-User", "le05751")
                .header("X-Auth-Roles", "MANAGER"))
            .andExpect(status().isOk());

        User stored = userRepo.findByUuName("le05751").orElseThrow();
        assertThat(stored.getFirstName()).isEqualTo("Alex");
        assertThat(stored.getLastName()).isEqualTo("Len");
        assertThat(stored.getEmail()).isEqualTo("alex.len@ubs.com");
        // The role always applies, so a revoked or changed entitlement takes effect immediately.
        assertThat(stored.getRole()).isEqualTo("Manager");
    }

    @Test
    void directoryListing_returnsStoredUsers() throws Exception {
        mvc.perform(asLeAnalyst()).andExpect(status().isOk());

        mvc.perform(get("/api/users")
                .header("X-Auth-User", "le05751")
                .header("X-Auth-First-Name", "Alex")
                .header("X-Auth-Last-Name", "Len")
                .header("X-Auth-Email", "alex.len@ubs.com")
                .header("X-Auth-Roles", "ANALYST"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].uuName").value("le05751"))
            .andExpect(jsonPath("$[0].displayName").value("Alex Len"))
            .andExpect(jsonPath("$[0].email").value("alex.len@ubs.com"))
            .andExpect(jsonPath("$[0].role").value("Analyst"));
    }

    @Test
    void unknownUuName_is404() throws Exception {
        mvc.perform(get("/api/users/{uuName}", "nobody")
                .header("X-Auth-User", "le05751")
                .header("X-Auth-Roles", "ANALYST"))
            .andExpect(status().isNotFound());
    }

    @Test
    void me_stillReportsTheCallerFromTheRequestPrincipal() throws Exception {
        mvc.perform(get("/api/users/me")
                .header("X-Auth-User", "le05751")
                .header("X-Auth-First-Name", "Alex")
                .header("X-Auth-Last-Name", "Len")
                .header("X-Auth-Email", "alex.len@ubs.com")
                .header("X-Auth-Roles", "MANAGER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uuName").value("le05751"))
            .andExpect(jsonPath("$.role").value("Account/Transaction Manager"));
    }
}
