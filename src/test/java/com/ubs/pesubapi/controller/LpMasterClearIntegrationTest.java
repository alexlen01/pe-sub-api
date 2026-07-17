package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Bootstrap repopulate path for the bank-wide LP Master: the one-off LP DB extract feed replaces
 * the whole table ("override, do not preserve"), so the pe-sub-jobs ingest job wipes it first via
 * this SERVICE-gated endpoint. The wipe must detach — never delete — the facilities' own LP
 * records (per-facility credit data) so the lp_records.lp_master_id foreign key is satisfied
 * before the master rows go.
 */
@WithMockUser(username = "jobs-svc", roles = {"SERVICE"})
class LpMasterClearIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc            mvc;
    @Autowired FacilityRepository facilityRepo;
    @Autowired LpMasterRepository lpMasterRepo;
    @Autowired LpRecordRepository lpRecordRepo;
    @Autowired AuditLogRepository auditLogRepo;

    private static final String LP = "Apex Public Employees Retirement System";   // TEST ONLY

    private int facilityId;
    private int recordId;

    @BeforeEach
    void seed() {
        Facility f = new Facility();
        f.setName("AG ABC");   // TEST ONLY
        f.setAgentBank("Societe Generale");
        facilityId = facilityRepo.save(f).getId();

        LpMaster master = new LpMaster();
        master.setInvestorName(LP);
        int masterId = lpMasterRepo.save(master).getId();

        // A second master row with no facility reference, to prove the wipe is table-wide.
        LpMaster orphan = new LpMaster();
        orphan.setInvestorName("Unreferenced Investor LP");   // TEST ONLY
        lpMasterRepo.save(orphan);

        LpRecord record = new LpRecord();
        record.setFacilityId(facilityId);
        record.setInvestorName(LP);
        record.setInvType("Pension");
        record.setRegion("Latin America");
        record.setCls("Rated");
        record.setLpMasterId(masterId);
        recordId = lpRecordRepo.save(record).getId();
    }

    @Test
    void clear_wipesAllMasters_detachesFacilityRecords_andAudits() throws Exception {
        mvc.perform(post("/api/lp-master/clear"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedMasters").value(2))
            .andExpect(jsonPath("$.detachedRecords").value(1));

        assertThat(lpMasterRepo.count()).isZero();

        // The facility's LP record survives — only its master reference is cleared.
        LpRecord survivor = lpRecordRepo.findById(recordId).orElseThrow();
        assertThat(survivor.getInvestorName()).isEqualTo(LP);
        assertThat(survivor.getLpMasterId()).isNull();

        assertThat(auditLogRepo.findAllByOrderByCreatedAtDesc())
            .anySatisfy(entry -> {
                assertThat(entry.getEvent()).isEqualTo("LP Master Cleared");
                assertThat(entry.getDetail())
                    .contains("2 LP Master row(s) removed")
                    .contains("1 facility LP record(s) detached");
            });
    }

    @Test
    void clear_onEmptyTable_isNoOp_returnsZeroCounts() throws Exception {
        lpRecordRepo.deleteAll();
        lpMasterRepo.deleteAll();

        mvc.perform(post("/api/lp-master/clear"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedMasters").value(0))
            .andExpect(jsonPath("$.detachedRecords").value(0));
    }

    @Test
    @WithMockUser(username = "analyst", roles = {"ANALYST"})
    void clear_isForbiddenForNonServiceRole() throws Exception {
        mvc.perform(post("/api/lp-master/clear"))
            .andExpect(status().isForbidden());

        // Data untouched when the caller lacks the SERVICE role.
        assertThat(lpMasterRepo.count()).isEqualTo(2);
    }
}
