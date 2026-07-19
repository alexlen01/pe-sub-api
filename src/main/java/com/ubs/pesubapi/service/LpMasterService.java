package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.CommitBbRequest.CommitLpRow;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LpMasterService {

    private static final Logger log = LoggerFactory.getLogger(LpMasterService.class);

    private final LpRecordRepository lpRecordRepo;
    private final LpMasterRepository lpMasterRepo;

    public LpMasterService(LpRecordRepository lpRecordRepo, LpMasterRepository lpMasterRepo) {
        this.lpRecordRepo = lpRecordRepo;
        this.lpMasterRepo = lpMasterRepo;
    }

    /** Outcome of an LP Master row deletion, for audit/notification messaging. */
    public record LpMasterDeletion(String investorName, int detachedRecords) {}

    /** Outcome of a full LP Master clear, for audit/notification messaging. */
    public record LpMasterClear(long deletedMasters, int detachedRecords) {}

    /**
     * Clears the entire LP Master table ahead of a bootstrap repopulate (the one-off LP DB
     * extract feed). Facility LP records that reference master rows are detached first
     * (lp_master_id nulled) — never deleted, as they are per-facility credit data — satisfying
     * the lp_records.lp_master_id foreign key before the master rows are removed. The next
     * accepted Shadow BB write-back re-links records to the freshly seeded master by name.
     */
    @Transactional
    public LpMasterClear clearAll() {
        int detached = lpRecordRepo.clearAllLpMasterRefs();
        long deleted = lpMasterRepo.count();
        lpMasterRepo.deleteAllInBatch();
        log.info("LP Master cleared deletedMasters={} detachedFacilityRecords={}", deleted, detached);
        return new LpMasterClear(deleted, detached);
    }

    /**
     * Hard-deletes an LP Master row — the manual correction path for erroneously ingested LPs
     * that slipped past analyst/reviewer checks. Facility LP records that reference the row are
     * detached (lp_master_id nulled), never deleted: they are per-facility credit data and remain
     * authoritative for their facility. Note that if the same investor name still exists in a
     * facility's records, the next accepted Shadow BB cycle's write-back will re-create a master
     * row from that facility's data.
     */
    @Transactional
    public LpMasterDeletion delete(int id) {
        LpMaster master = lpMasterRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("LP Master " + id + " not found"));
        int detached = lpRecordRepo.clearLpMasterRef(id);
        lpMasterRepo.delete(master);
        log.info("LP Master deleted id={} investor='{}' detachedFacilityRecords={}",
            id, master.getInvestorName(), detached);
        return new LpMasterDeletion(master.getInvestorName(), detached);
    }

    /**
     * Upserts LP records for a facility from a Shadow BB run.
     * Matches by (facilityId, investorName): updates existing LPs in place (preserving their IDs
     * so FK references from match_queue_entries are not broken), inserts new LPs.
     * Incoming rows are themselves deduped by name so the same Agent BB submitted twice (or a
     * name repeated within one payload) collapses onto a single record — last value wins —
     * never violating the uq_lp_records_facility_investor constraint.
     */
    @Transactional
    public List<LpRecord> upsertAll(int facilityId, List<CommitLpRow> rows) {
        Map<String, LpRecord> byName = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).stream()
            .collect(Collectors.toMap(lpRecord -> lpRecord.getInvestorName(), lpRecord -> lpRecord, (a, b) -> a, LinkedHashMap::new));

        Map<String, LpRecord> toSave = new LinkedHashMap<>();
        int seq = 0;
        for (CommitLpRow row : rows) {
            String name = row.name() != null ? row.name() : "";
            // DEBUG carries the full record payload so a per-record persistence failure
            // (e.g. "value too long for character varying(N)") is attributable to a specific
            // LP in lower environments; INFO+ (higher environments) logs nothing per record.
            log.debug("Upserting LP record: facilityId={} seq={} name='{}' row={}",
                facilityId, seq, name, row);
            LpRecord lpRecord = byName.computeIfAbsent(name, n -> new LpRecord());
            apply(lpRecord, facilityId, row);
            lpRecord.setSourceSeq(seq++);   // preserve the submitted (source-file) order
            toSave.put(name, lpRecord);
        }
        log.debug("Upsert complete: facilityId={} submittedRows={} distinctRecords={}",
            facilityId, rows.size(), toSave.size());
        return lpRecordRepo.saveAll(new ArrayList<>(toSave.values()));
    }

    private void apply(LpRecord lpRecord, int facilityId, CommitLpRow row) {
        lpRecord.setFacilityId(facilityId);
        lpRecord.setInvestorName(row.name() != null ? row.name() : "");
        lpRecord.setParent(row.parent());
        lpRecord.setSpv(row.spv());
        lpRecord.setHighQty(row.hq());
        lpRecord.setFundSleeve(row.fundSleeve());
        lpRecord.setInvestorType(row.investorType() != null ? row.investorType() : "");
        lpRecord.setInstVsHnw(row.instVsHnw() != null ? row.instVsHnw() : "Institutional");
        lpRecord.setRegionLocation(row.regionLocation() != null ? row.regionLocation() : "");
        lpRecord.setIg(row.ig());
        lpRecord.setCls(row.cls() != null ? row.cls() : "Eligible");
        lpRecord.setAgentCls(row.agentCls());
        lpRecord.setAgentClsSource(normalizeAgentClsSource(row.agentClsSource()));
        lpRecord.setSp(row.sp()    != null ? row.sp()    : "");
        lpRecord.setMdy(row.mdy()  != null ? row.mdy()   : "");
        lpRecord.setFitch(row.fitch() != null ? row.fitch() : "");
        lpRecord.setAum(row.aum());
        lpRecord.setNav(row.nav());
        lpRecord.setPension(row.pension());
        lpRecord.setPensionFunded(row.pensionFunded());
        lpRecord.setCapCommit(row.capCommit());
        lpRecord.setPctCapCommit(row.pctCapCommit());
        lpRecord.setCalledCap(row.calledCap());
        lpRecord.setUc(row.uc());
        lpRecord.setPctUncalled(row.pctUncalled());
        lpRecord.setPctCalled(row.pctCalled());
        lpRecord.setAgentConc(row.agentConc());
        lpRecord.setUbsConc(row.ubsConc());
        // Persist the resolved UBS advance rate alongside the conc limit so the accepted run's
        // credit profile round-trips to LP Master (writeBackToLpMaster reads ubsRate). Guarded so an
        // older client that omits the field does not clear a previously stored per-LP rate.
        if (row.ubsRate() != null && !row.ubsRate().isBlank()) {
            lpRecord.setUbsRate(row.ubsRate());
        }
        lpRecord.setAgentRate(row.agentRate());
        // C2: keep the precise numeric columns in lockstep with the display strings written here.
        // This run's payload carries only formatted strings, so numeric is derived from them —
        // critically clearing any stale numeric left by a prior extraction-commit cycle, which
        // would otherwise be read in preference to the string just written.
        lpRecord.setUcNum(numDollars(row.uc()));
        lpRecord.setCapCommitNum(numDollars(row.capCommit()));
        lpRecord.setAumNum(numDollars(row.aum()));
        // Engine outputs (abb/abbNum, ubb, excess concentrations, rank) are not taken from the
        // payload: abb stays whatever ingest wrote (agent-reported), and the rest are computed
        // and written back by ShadowBbService in the same run transaction.
        lpRecord.setInc(row.inc());
        lpRecord.setRcl(row.rcl());
        lpRecord.setTf(row.tf());
        lpRecord.setNotes(row.notes());
    }

    /** Formatted money string → absolute dollars, or null when blank/unparseable so the numeric
     *  column is cleared and the engine falls back to the display string. */
    private static BigDecimal numDollars(String display) {
        double millions = BbCalculationService.parseMoney(display);
        return millions == 0 ? null : BigDecimal.valueOf(millions * 1_000_000.0);
    }

    private static String normalizeAgentClsSource(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "EXTRACTED", "DERIVED", "USER_EDITED" -> value;
            default -> null;
        };
    }
}
