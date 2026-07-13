package com.ubs.pesubapi.security;

import com.ubs.pesubapi.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IT read-only role (Intra ID App Role {@code APP_VIEWER}, Spring role {@code VIEWER}).
 *
 * <p>The contract: a VIEWER may GET/download anything but is denied every mutating verb
 * (create / edit / delete / upload) with 403. This exercises both sides of that boundary.
 */
@WithMockUser(username = "it.support@ubs.com", roles = {"VIEWER"})
class ViewerRoleIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    // ── Reads / downloads are allowed ─────────────────────────────────────────

    @Test
    void viewer_canListFacilities() throws Exception {
        mvc.perform(get("/api/facilities")).andExpect(status().isOk());
    }

    @Test
    void viewer_canReadConfiguration() throws Exception {
        mvc.perform(get("/api/config/classification")).andExpect(status().isOk());
    }

    @Test
    void viewer_canReadTemplates() throws Exception {
        mvc.perform(get("/api/bb-templates")).andExpect(status().isOk());
    }

    // ── Every mutating verb is denied ─────────────────────────────────────────

    @Test
    void viewer_cannotCreateFacility() throws Exception {
        mvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"agentBank\":\"Y\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewer_cannotEditConfiguration() throws Exception {
        mvc.perform(put("/api/config/eligibility?section=GLOBAL_SETTINGS")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewer_cannotPatchLpRecord() throws Exception {
        mvc.perform(patch("/api/lpRecords/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewer_cannotDeleteLpMaster() throws Exception {
        mvc.perform(delete("/api/lp-master/1")).andExpect(status().isForbidden());
    }

    @Test
    void viewer_cannotAcceptReview() throws Exception {
        mvc.perform(post("/api/submissions/1/accept")).andExpect(status().isForbidden());
    }

    @Test
    void viewer_canRecordOwnLogin() throws Exception {
        // A self-login audit event is allowed for any authenticated role (attribution, not mutation).
        mvc.perform(post("/api/audit/login")).andExpect(status().is2xxSuccessful());
    }
}
