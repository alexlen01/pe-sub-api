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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Manual correction path for the bank-wide LP Master: no matter how many analyst/reviewer
 * checks the ingestion pipeline has, an erroneous row can slip through, so a hard delete
 * must exist. Deleting a master row must never delete the facilities' own LP records —
 * they are per-facility credit data — it only detaches their {@code lp_master_id} reference.
 */
class LpMasterDeleteIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc              mvc;
    @Autowired FacilityRepository   facilityRepo;
    @Autowired LpMasterRepository   lpMasterRepo;
    @Autowired LpRecordRepository   lpRecordRepo;
    @Autowired AuditLogRepository   auditLogRepo;

    private static final String LP = "Erroneously Ingested Investors LP";   // TEST ONLY

    private int facilityId;
    private int masterId;
    private int recordId;

    @BeforeEach
    void seed() {
        Facility f = new Facility();
        f.setName("Apex Growth Fund V");   // TEST ONLY
        f.setAgentBank("Morgan Stanley");
        facilityId = facilityRepo.save(f).getId();

        LpMaster master = new LpMaster();
        master.setInvestorName(LP);
        master.setUbsLpCategory("Rated Investor");
        masterId = lpMasterRepo.save(master).getId();

        LpRecord record = new LpRecord();
        record.setFacilityId(facilityId);
        record.setInvestorName(LP);
        record.setInvestorSegmentOrType("Pension");
        record.setRegionLocation("US");
        record.setUbsLpCategory("Rated");
        record.setLpMasterId(masterId);
        recordId = lpRecordRepo.save(record).getId();
    }

    @Test
    void delete_removesMasterRow_detachesFacilityRecords_andAudits() throws Exception {
        mvc.perform(delete("/api/lp-master/{id}", masterId))
            .andExpect(status().isNoContent());

        assertThat(lpMasterRepo.findById(masterId)).isEmpty();

        // The facility's LP record survives — only its master reference is cleared.
        LpRecord survivor = lpRecordRepo.findById(recordId).orElseThrow();
        assertThat(survivor.getInvestorName()).isEqualTo(LP);
        assertThat(survivor.getLpMasterId()).isNull();

        assertThat(auditLogRepo.findAllByOrderByCreatedAtDesc())
            .anySatisfy(entry -> {
                assertThat(entry.getEvent()).isEqualTo("LP Master Deleted");
                assertThat(entry.getDetail()).contains(LP).contains("1 facility LP record(s) detached");
            });
    }

    @Test
    void delete_unknownId_returns404_andLeavesDataUntouched() throws Exception {
        mvc.perform(delete("/api/lp-master/{id}", 999_999))
            .andExpect(status().isNotFound());

        assertThat(lpMasterRepo.findById(masterId)).isPresent();
        assertThat(lpRecordRepo.findById(recordId).orElseThrow().getLpMasterId()).isEqualTo(masterId);
    }

    @Test
    void delete_masterWithNoFacilityReferences_reportsZeroDetached() throws Exception {
        LpMaster orphan = new LpMaster();
        orphan.setInvestorName("Unreferenced Investor LP");   // TEST ONLY
        int orphanId = lpMasterRepo.save(orphan).getId();

        mvc.perform(delete("/api/lp-master/{id}", orphanId))
            .andExpect(status().isNoContent());

        assertThat(lpMasterRepo.findById(orphanId)).isEmpty();
        assertThat(auditLogRepo.findAllByOrderByCreatedAtDesc())
            .anySatisfy(entry ->
                assertThat(entry.getDetail()).contains("Unreferenced Investor LP").contains("0 facility LP record(s) detached"));
    }
}
