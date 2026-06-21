package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.CommitBbRequest.CommitLpRow;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.repository.LpRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LpMasterService {

    private final LpRepository lpRepo;

    public LpMasterService(LpRepository lpRepo) {
        this.lpRepo = lpRepo;
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
    public List<Lp> upsertAll(int facilityId, List<CommitLpRow> rows) {
        Map<String, Lp> byName = lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).stream()
            .collect(Collectors.toMap(Lp::getInvestorName, lp -> lp, (a, b) -> a, LinkedHashMap::new));

        Map<String, Lp> toSave = new LinkedHashMap<>();
        int seq = 0;
        for (CommitLpRow row : rows) {
            String name = row.name() != null ? row.name() : "";
            Lp lp = byName.computeIfAbsent(name, n -> new Lp());
            apply(lp, facilityId, row);
            lp.setSourceSeq(seq++);   // preserve the submitted (source-file) order
            toSave.put(name, lp);
        }
        return lpRepo.saveAll(new ArrayList<>(toSave.values()));
    }

    private void apply(Lp lp, int facilityId, CommitLpRow row) {
        lp.setFacilityId(facilityId);
        lp.setInvestorName(row.name() != null ? row.name() : "");
        lp.setParent(row.parent());
        lp.setSpv(row.spv());
        lp.setHighQty(row.hq());
        lp.setInvType(row.type() != null ? row.type() : "Institutional");
        lp.setRegion(row.region() != null ? row.region() : "");
        lp.setIg(row.ig());
        lp.setCls(row.cls() != null ? row.cls() : "Eligible");
        lp.setAgentCls(row.agentCls());
        lp.setSp(row.sp()    != null ? row.sp()    : "");
        lp.setMdy(row.mdy()  != null ? row.mdy()   : "");
        lp.setFitch(row.fitch() != null ? row.fitch() : "");
        lp.setAum(row.aum());
        lp.setNav(row.nav());
        lp.setPension(row.pension());
        lp.setPensionFunded(row.pensionFunded());
        lp.setCapCommit(row.capCommit());
        lp.setPctCapCommit(row.pctCapCommit());
        lp.setCalledCap(row.calledCap());
        lp.setUc(row.uc());
        lp.setPctUncalled(row.pctUncalled());
        lp.setPctCalled(row.pctCalled());
        lp.setAgentConc(row.agentConc());
        lp.setUbsConc(row.ubsConc());
        lp.setAgentRate(row.agentRate());
        lp.setAbb(row.abb());
        lp.setUbb(row.ubb());
        lp.setAgentExcessConc(row.agentExcessConc());
        lp.setUbsExcessConc(row.ubsExcessConc());
        lp.setInc(row.inc());
        lp.setRcl(row.rcl());
        lp.setNotes(row.notes());
    }
}
