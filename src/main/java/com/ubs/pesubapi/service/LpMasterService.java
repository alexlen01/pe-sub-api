package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.CommitBbRequest.CommitLpRow;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.repository.LpRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
     * Rank is computed dynamically in Shadow BB (uncalled capital desc) and not stored.
     */
    @Transactional
    public List<Lp> upsertAll(int facilityId, List<CommitLpRow> rows) {
        List<Lp> existing = lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId);
        Map<String, Lp> byName = existing.stream()
            .collect(Collectors.toMap(Lp::getInvestorName, lp -> lp, (a, b) -> a));

        List<Lp> toSave = new ArrayList<>(rows.size());
        for (CommitLpRow row : rows) {
            Lp lp = byName.getOrDefault(row.investorName(), new Lp());
            apply(lp, facilityId, row);
            toSave.add(lp);
        }
        return lpRepo.saveAll(toSave);
    }

    private void apply(Lp lp, int facilityId, CommitLpRow row) {
        lp.setFacilityId(facilityId);
        lp.setInvestorName(row.investorName() != null ? row.investorName() : "");
        lp.setParent(row.parent());
        lp.setSpv(row.spv());
        lp.setHighQty(row.highQty());
        lp.setInvType(row.invType() != null ? row.invType() : "Institutional");
        lp.setRegion(row.region() != null ? row.region() : "");
        lp.setIg(row.ig());
        lp.setCls(row.cls() != null ? row.cls() : "Eligible");
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
        lp.setInc(row.inc());
        lp.setRcl(row.rcl());
        lp.setNotes(row.notes());
    }
}
