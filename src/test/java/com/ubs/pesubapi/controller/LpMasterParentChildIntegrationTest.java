package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.repository.LpAliasRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Parent/child resolution on {@code lp_master} — Phase 3 and Part 2 of
 * {@code pe-sub-docs/LP_Mapping_and_Database_Architecture.md}.
 *
 * <p>Pins the three facts the mapping design turns on: the hierarchy round-trips through the API,
 * the ultimate parent is resolved for the Review Matches "to be applied" column, and a link that
 * would close a cycle is rejected rather than producing a hierarchy with no ultimate entity.
 */
class LpMasterParentChildIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc            mvc;
    @Autowired LpMasterRepository lpMasterRepo;
    @Autowired LpAliasRepository  aliasRepo;

    // TEST ONLY — a sponsor, its feeder, and a second-level SPV beneath the feeder.
    private static final String SPONSOR = "Apollo Global Management";
    private static final String FEEDER  = "Apollo GRE IV, LP";
    private static final String SPV     = "Apollo GRE IV Offshore SPV";

    private LpMaster save(String name, String parentName, Integer parentId) {
        LpMaster m = new LpMaster();
        m.setInvestorName(name);
        m.setParent(parentName);
        if (parentId != null) m.setParentId(parentId);
        return lpMasterRepo.save(m);
    }

    private String updatePayload(String investorName, String parent, Integer parentId) {
        return """
            {
              "investorName": "%s",
              "parent": %s,
              "parentId": %s,
              "spv": false, "highQuality": true, "investmentGrade": false,
              "investorType": "Private Equity", "institutionalOrHnw": "Institutional",
              "regionLocation": "North America", "ubsLpCategory": "Rated Investor",
              "spRating": "", "moodysRating": "", "fitchRating": "",
              "aum": null, "nav": null, "pensionAssets": null, "fundingRatio": null,
              "ubsDefaultAdvanceRate": null, "ubsDefaultConcentrationLimit": null,
              "notes": null
            }
            """.formatted(investorName,
                parent == null ? "null" : "\"" + parent + "\"",
                parentId == null ? "null" : parentId.toString());
    }

    // ── Hierarchy round-trips ────────────────────────────────────────────────────────

    @Test
    void listResolvesUltimateParentAndChildCount() throws Exception {
        LpMaster sponsor = save(SPONSOR, null, null);
        LpMaster feeder  = save(FEEDER, SPONSOR, sponsor.getId());
        save(SPV, FEEDER, feeder.getId());

        // The sponsor is its own ultimate entity and reports one direct child.
        mvc.perform(get("/api/lp-master"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.investorName=='" + SPONSOR + "')].ultimateParent").value(
                org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
            .andExpect(jsonPath("$[?(@.investorName=='" + SPONSOR + "')].isUltimateParent").value(
                org.hamcrest.Matchers.contains(true)))
            .andExpect(jsonPath("$[?(@.investorName=='" + SPONSOR + "')].childCount").value(
                org.hamcrest.Matchers.contains(1)))
            // Two levels down, the walk still lands on the sponsor — not the intermediate feeder.
            .andExpect(jsonPath("$[?(@.investorName=='" + SPV + "')].ultimateParent").value(
                org.hamcrest.Matchers.contains(SPONSOR)))
            .andExpect(jsonPath("$[?(@.investorName=='" + SPV + "')].parent").value(
                org.hamcrest.Matchers.contains(FEEDER)));
    }

    @Test
    void childrenEndpointListsDirectFeedersOnly() throws Exception {
        LpMaster sponsor = save(SPONSOR, null, null);
        LpMaster feeder  = save(FEEDER, SPONSOR, sponsor.getId());
        save(SPV, FEEDER, feeder.getId());

        // Direct children only — the second-level SPV hangs off the feeder, not the sponsor.
        mvc.perform(get("/api/lp-master/{id}/children", sponsor.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].investorName").value(FEEDER));
    }

    // ── Editing the link ─────────────────────────────────────────────────────────────

    @Test
    void updateLinksParentByNameWhenNoIdSupplied() throws Exception {
        LpMaster sponsor = save(SPONSOR, null, null);
        LpMaster feeder  = save(FEEDER, null, null);

        mvc.perform(put("/api/lp-master/{id}", feeder.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(FEEDER, SPONSOR, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parentId").value(sponsor.getId()))
            .andExpect(jsonPath("$.ultimateParent").value(SPONSOR))
            .andExpect(jsonPath("$.isUltimateParent").value(false));

        assertThat(lpMasterRepo.findById(feeder.getId()).orElseThrow().getParentId())
            .isEqualTo(sponsor.getId());
    }

    @Test
    void updateRetainsUnresolvedParentNameAsDisplayOnly() throws Exception {
        LpMaster feeder = save(FEEDER, null, null);

        // A sponsor typed before it exists must not be silently discarded: the name is kept and
        // the row still reads as its own ultimate entity until the sponsor is created.
        mvc.perform(put("/api/lp-master/{id}", feeder.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(FEEDER, "Sponsor Not Yet Onboarded", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parent").value("Sponsor Not Yet Onboarded"))
            .andExpect(jsonPath("$.parentId").doesNotExist())
            .andExpect(jsonPath("$.isUltimateParent").value(true));
    }

    @Test
    void creatingTheSponsorLaterAdoptsPendingChildren() throws Exception {
        LpMaster feeder  = save(FEEDER, SPONSOR, null);   // names a sponsor that does not exist yet
        LpMaster sponsor = save(SPONSOR, null, null);

        // Saving the sponsor closes the link its children were already naming.
        mvc.perform(put("/api/lp-master/{id}", sponsor.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(SPONSOR, null, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.childCount").value(1));

        assertThat(lpMasterRepo.findById(feeder.getId()).orElseThrow().getParentId())
            .isEqualTo(sponsor.getId());
    }

    @Test
    void renamingASponsorRepointsItsChildrenDisplayString() throws Exception {
        LpMaster sponsor = save(SPONSOR, null, null);
        LpMaster feeder  = save(FEEDER, SPONSOR, sponsor.getId());

        mvc.perform(put("/api/lp-master/{id}", sponsor.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload("Apollo Global Management Inc.", null, null)))
            .andExpect(status().isOk());

        // The link was already by id; the child's display string must not be left stale.
        assertThat(lpMasterRepo.findById(feeder.getId()).orElseThrow().getParent())
            .isEqualTo("Apollo Global Management Inc.");
    }

    @Test
    void recordNamingItselfAsParentSavesAndStaysUltimate() throws Exception {
        // The live LP Master feed uses a record's own name in `parent` to mean "no parent" — about
        // 2,500 of ~6,000 rows carry it. Re-saving such a record must not be rejected as a
        // self-reference, and the string is preserved rather than normalised away.
        LpMaster m = save(SPONSOR, SPONSOR, null);

        mvc.perform(put("/api/lp-master/{id}", m.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(SPONSOR, SPONSOR, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parent").value(SPONSOR))
            .andExpect(jsonPath("$.parentId").doesNotExist())
            .andExpect(jsonPath("$.isUltimateParent").value(true))
            .andExpect(jsonPath("$.ultimateParent").doesNotExist());

        assertThat(lpMasterRepo.findById(m.getId()).orElseThrow().getParentId()).isNull();
    }

    @Test
    void selfNamingRowIsNotAdoptedAsItsOwnChild() throws Exception {
        // Bulk relink must apply the same rule, or the feed would link every such row to itself.
        LpMaster m = save(SPONSOR, SPONSOR, null);

        mvc.perform(post("/api/lp-master/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"investorName": "%s", "parent": "%s", "spv": false, "highQuality": true,
                      "investmentGrade": false, "ubsLpCategory": "Rated Investor"}]
                    """.formatted(SPONSOR, SPONSOR)))
            .andExpect(status().isOk());

        assertThat(lpMasterRepo.findById(m.getId()).orElseThrow().getParentId()).isNull();
    }

    @Test
    void selfParentIsRejected() throws Exception {
        LpMaster sponsor = save(SPONSOR, null, null);

        mvc.perform(put("/api/lp-master/{id}", sponsor.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(SPONSOR, SPONSOR, sponsor.getId())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void cycleIsRejected() throws Exception {
        LpMaster sponsor = save(SPONSOR, null, null);
        LpMaster feeder  = save(FEEDER, SPONSOR, sponsor.getId());

        // Pointing the sponsor at its own feeder would leave the pair with no ultimate entity,
        // and therefore no credit profile to apply to a matched record.
        mvc.perform(put("/api/lp-master/{id}", sponsor.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(SPONSOR, FEEDER, feeder.getId())))
            .andExpect(status().isBadRequest());

        assertThat(lpMasterRepo.findById(sponsor.getId()).orElseThrow().getParentId()).isNull();
    }

    @Test
    void clearingTheParentRestoresUltimateStatus() throws Exception {
        LpMaster sponsor = save(SPONSOR, null, null);
        LpMaster feeder  = save(FEEDER, SPONSOR, sponsor.getId());

        mvc.perform(put("/api/lp-master/{id}", feeder.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(FEEDER, null, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parentId").doesNotExist())
            .andExpect(jsonPath("$.isUltimateParent").value(true))
            .andExpect(jsonPath("$.ultimateParent").doesNotExist());
    }

    @Test
    void updatePersistsEditableCreditProfileFields() throws Exception {
        LpMaster m = save(SPONSOR, null, null);

        String payload = """
            {
              "investorName": "%s",
              "parent": null, "parentId": null,
              "spv": true, "highQuality": false, "investmentGrade": true,
              "investorType": "Sovereign Wealth Fund", "institutionalOrHnw": "Institutional",
              "regionLocation": "EMEA", "ubsLpCategory": "Rated Investor",
              "spRating": "AA-", "moodysRating": "Aa3", "fitchRating": "AA",
              "aum": "$500.0B", "nav": null, "pensionAssets": null, "fundingRatio": 0.9100,
              "ubsDefaultAdvanceRate": 0.9000, "ubsDefaultConcentrationLimit": "7.5%%",
              "notes": "Curated by credit."
            }
            """.formatted(SPONSOR);

        mvc.perform(put("/api/lp-master/{id}", m.getId())
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk());

        // Round-trip every field the panel edits, so a dropped mapping fails here rather than
        // silently blanking a curated profile.
        mvc.perform(get("/api/lp-master/{id}", m.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.spv").value(true))
            .andExpect(jsonPath("$.highQuality").value(false))
            .andExpect(jsonPath("$.investmentGrade").value(true))
            .andExpect(jsonPath("$.investorType").value("Sovereign Wealth Fund"))
            .andExpect(jsonPath("$.regionLocation").value("EMEA"))
            .andExpect(jsonPath("$.ubsLpCategory").value("Rated Investor"))
            .andExpect(jsonPath("$.spRating").value("AA-"))
            .andExpect(jsonPath("$.moodysRating").value("Aa3"))
            .andExpect(jsonPath("$.fitchRating").value("AA"))
            .andExpect(jsonPath("$.aum").value("$500.0B"))
            .andExpect(jsonPath("$.notes").value("Curated by credit."));

        LpMaster saved = lpMasterRepo.findById(m.getId()).orElseThrow();
        assertThat(saved.getFundingRatio()).isEqualByComparingTo(new BigDecimal("0.9100"));
        assertThat(saved.getUbsDefaultAdvanceRate()).isEqualByComparingTo(new BigDecimal("0.9000"));
        // A percent-style limit stays on the percent scale, not the fraction scale.
        assertThat(saved.getUbsDefaultConcentrationLimit()).isEqualByComparingTo(new BigDecimal("7.5"));
    }

    @Test
    void updateOnUnknownIdIs404() throws Exception {
        mvc.perform(put("/api/lp-master/{id}", 999_999)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload("Nobody", null, null)))
            .andExpect(status().isNotFound());
    }

    // ── Bulk ingest links parents regardless of feed order ───────────────────────────

    @Test
    void ingestLinksChildIngestedBeforeItsSponsor() throws Exception {
        // The child arrives first, naming a sponsor that does not exist yet.
        String feed = """
            [
              {"investorName": "%s", "parent": "%s", "spv": false, "highQuality": true,
               "investmentGrade": false, "ubsLpCategory": "Rated Investor"},
              {"investorName": "%s", "parent": null, "spv": false, "highQuality": true,
               "investmentGrade": false, "ubsLpCategory": "Rated Investor"}
            ]
            """.formatted(FEEDER, SPONSOR, SPONSOR);

        mvc.perform(post("/api/lp-master/ingest")
                .contentType(MediaType.APPLICATION_JSON).content(feed))
            .andExpect(status().isOk());

        LpMaster sponsor = lpMasterRepo.findByInvestorName(SPONSOR).orElseThrow();
        assertThat(lpMasterRepo.findByInvestorName(FEEDER).orElseThrow().getParentId())
            .isEqualTo(sponsor.getId());
    }

    // ── Alias feedback loop ──────────────────────────────────────────────────────────

    @Test
    void aliasesEndpointReturnsAcceptedAgentStrings() throws Exception {
        LpMaster feeder = save(FEEDER, null, null);
        aliasRepo.save(new com.ubs.pesubapi.entity.LpAlias(feeder.getId(), "APOLLO GRE IV"));

        mvc.perform(get("/api/lp-master/{id}/aliases", feeder.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0]").value("APOLLO GRE IV"));
    }

    @Test
    void deletingAnLpMasterRowRemovesItsAliases() throws Exception {
        LpMaster feeder = save(FEEDER, null, null);
        aliasRepo.save(new com.ubs.pesubapi.entity.LpAlias(feeder.getId(), "APOLLO GRE IV"));

        mvc.perform(delete("/api/lp-master/{id}", feeder.getId()))
            .andExpect(status().isNoContent());

        assertThat(aliasRepo.findByUploadedName("APOLLO GRE IV")).isEmpty();
    }
}
