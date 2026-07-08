package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.CommitBbRequest.CommitLpRow;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.repository.LpRecordRepository;
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

    private final LpRecordRepository lpRecordRepo;

    public LpMasterService(LpRecordRepository lpRecordRepo) {
        this.lpRecordRepo = lpRecordRepo;
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
            LpRecord lpRecord = byName.computeIfAbsent(name, n -> new LpRecord());
            apply(lpRecord, facilityId, row);
            lpRecord.setSourceSeq(seq++);   // preserve the submitted (source-file) order
            toSave.put(name, lpRecord);
        }
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
        lpRecord.setAgentRate(row.agentRate());
        lpRecord.setAbb(row.abb());
        // C2: keep the precise numeric columns in lockstep with the display strings written here.
        // This run's payload carries only formatted strings, so numeric is derived from them —
        // critically clearing any stale numeric left by a prior extraction-commit cycle, which
        // would otherwise be read in preference to the string just written.
        lpRecord.setUcNum(numDollars(row.uc()));
        lpRecord.setCapCommitNum(numDollars(row.capCommit()));
        lpRecord.setAumNum(numDollars(row.aum()));
        lpRecord.setAbbNum(numDollars(row.abb()));
        lpRecord.setUbb(row.ubb());
        lpRecord.setAgentExcessConc(row.agentExcessConc());
        lpRecord.setUbsExcessConc(row.ubsExcessConc());
        lpRecord.setInc(row.inc());
        lpRecord.setRcl(row.rcl());
        lpRecord.setTf(row.tf());
        lpRecord.setRank(row.rank());
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
