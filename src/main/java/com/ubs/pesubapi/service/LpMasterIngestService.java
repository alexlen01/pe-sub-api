package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.IngestSummary;
import com.ubs.pesubapi.dto.LpMasterIngestRow;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.repository.LpMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Bulk upsert of bank-wide LP Master profiles from the pe-sub-jobs feed, keyed by investor name.
 * Replaces the batch job's direct {@code INSERT ... ON CONFLICT (investor_name)} against the
 * lp_master table — pe-sub-api owns the schema, so all writes route through here.
 */
@Service
public class LpMasterIngestService {

    private final LpMasterRepository lpMasterRepo;

    public LpMasterIngestService(LpMasterRepository lpMasterRepo) {
        this.lpMasterRepo = lpMasterRepo;
    }

    /**
     * Upserts each row by investor name. The feed is the authoritative source for the whole
     * profile, so every field is overwritten — including with nulls — on update, exactly as the
     * previous ON CONFLICT upsert did. Rows without an investor name are skipped.
     */
    @Transactional
    public IngestSummary ingest(List<LpMasterIngestRow> rows) {
        int created = 0, updated = 0, skipped = 0;
        for (LpMasterIngestRow row : rows) {
            if (row == null || row.investorName() == null || row.investorName().isBlank()) {
                skipped++;
                continue;
            }
            String name = row.investorName().trim();
            // orElseGet keeps the variable provably non-null for static analysis; a new
            // (unsaved) entity is recognised by its not-yet-generated id.
            LpMaster lp = lpMasterRepo.findByInvestorName(name).orElseGet(() -> {
                LpMaster fresh = new LpMaster();
                fresh.setInvestorName(name);
                return fresh;
            });
            boolean isNew = lp.getId() == null;
            lp.setParent(row.parent());
            lp.setSpv(row.spv());
            lp.setHighQty(row.highQty());
            lp.setInvestorType(row.investorType());
            lp.setInstVsHnw(row.instVsHnw());
            lp.setRegionLocation(row.regionLocation());
            lp.setIg(row.investmentGrade());
            lp.setSp(row.sp());
            lp.setMdy(row.mdy());
            lp.setFitch(row.fitch());
            lp.setAum(row.aum());
            lp.setNav(row.nav());
            lp.setPension(row.pension());
            lp.setPensionFunded(row.pensionFunded());
            lp.setUbsClassification(row.ubsClassification());
            lp.setUbsDefaultAdvRate(row.ubsDefaultAdvRate());
            lp.setUbsDefaultConcLimit(row.ubsDefaultConcLimit());
            lp.setNotes(row.notes());
            lpMasterRepo.save(lp);
            if (isNew) created++; else updated++;
        }
        return new IngestSummary(created, updated, skipped);
    }
}
