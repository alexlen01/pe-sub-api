package com.ubs.pesubapi.service;

import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Synchronises the bank-wide {@code lp_master} store from a facility's current {@code lp_records}.
 *
 * <p>This is the single place UBS credit-profile decisions (classification, advance rate,
 * concentration limit) and refreshed identity/rating/scale fields flow back to LP Master. It is
 * invoked from every point a facility's LP records reach a settled state:
 * <ul>
 *   <li>a Shadow BB run / re-run ({@code ShadowBbService.runAndSnapshot}),</li>
 *   <li>acceptance of a submission ({@code SubmissionController /complete}),</li>
 *   <li>a manual LP Category &amp; Rate save flush ({@code LpClassificationService}).</li>
 * </ul>
 * The operation is idempotent: re-running it writes the same values, so callers may invoke it
 * freely without tracking whether LP Master is already current.
 */
@Service
public class LpMasterWriteBackService {

    private final LpRecordRepository lpRecordRepo;
    private final LpMasterRepository lpMasterRepo;

    public LpMasterWriteBackService(LpRecordRepository lpRecordRepo, LpMasterRepository lpMasterRepo) {
        this.lpRecordRepo = lpRecordRepo;
        this.lpMasterRepo = lpMasterRepo;
    }

    /**
     * For each LP record in the facility: if an LP Master row exists, refresh its UBS
     * classification / advance rate / concentration limit (and stable identity / rating / scale
     * fields) from the facility record. If no LP Master row exists yet (the LP record was new in
     * this cycle), create one so future submissions across any facility inherit these decisions.
     * The {@code lp_master_id} FK is stamped onto the facility record after upsert.
     */
    @Transactional
    public void writeBack(int facilityId) {
        List<LpRecord> facilityRecords = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId);
        if (facilityRecords.isEmpty()) return;

        List<String> investorNames = new ArrayList<>();
        for (LpRecord lpRecord : facilityRecords) {
            String investorName = lpRecord.getInvestorName();
            if (investorName != null && !investorName.isBlank() && !investorNames.contains(investorName)) {
                investorNames.add(investorName);
            }
        }

        Map<String, LpMaster> mastersByName = new LinkedHashMap<>();
        for (LpMaster master : lpMasterRepo.findByInvestorNameIn(investorNames)) {
            String investorName = master.getInvestorName();
            if (investorName != null && !investorName.isBlank()) {
                mastersByName.putIfAbsent(investorName, master);
            }
        }

        Map<String, LpMaster> stagedMasters = new LinkedHashMap<>();
        for (LpRecord lpRecord : facilityRecords) {
            String investorName = lpRecord.getInvestorName();
            if (investorName == null || investorName.isBlank()) continue;

            LpMaster master = stagedMasters.computeIfAbsent(investorName, name -> {
                LpMaster existing = mastersByName.get(name);
                if (existing != null) return existing;
                LpMaster fresh = new LpMaster();
                fresh.setInvestorName(name);
                return fresh;
            });

            mergeFacilityRecordIntoMaster(master, lpRecord);
        }

        List<LpMaster> savedMasters = lpMasterRepo.saveAll(new ArrayList<>(stagedMasters.values()));
        Map<String, Integer> masterIdsByName = new LinkedHashMap<>();
        for (LpMaster master : savedMasters) {
            String investorName = master.getInvestorName();
            Integer masterId = master.getId();
            if (investorName != null && !investorName.isBlank() && masterId != null) {
                masterIdsByName.putIfAbsent(investorName, masterId);
            }
        }

        List<LpRecord> recordsToUpdate = new ArrayList<>();
        for (LpRecord lpRecord : facilityRecords) {
            Integer masterId = masterIdsByName.get(lpRecord.getInvestorName());
            if (masterId != null && !Objects.equals(masterId, lpRecord.getLpMasterId())) {
                lpRecord.setLpMasterId(masterId);
                recordsToUpdate.add(lpRecord);
            }
        }
        if (!recordsToUpdate.isEmpty()) {
            lpRecordRepo.saveAll(recordsToUpdate);
        }
    }

    private void mergeFacilityRecordIntoMaster(LpMaster master, LpRecord lpRecord) {
        // UBS credit profile — the definitive output of the accepted Shadow BB cycle
        if (lpRecord.getCls()     != null && !lpRecord.getCls().isBlank())     master.setUbsClassification(lpRecord.getCls());
        if (lpRecord.getUbsRate() != null && !lpRecord.getUbsRate().isBlank()) master.setUbsDefaultAdvRate(lpRecord.getUbsRate());
        if (lpRecord.getUbsConc() != null && !lpRecord.getUbsConc().isBlank()) master.setUbsDefaultConcLimit(lpRecord.getUbsConc());

        // Stable identity — refresh blanks with anything the facility record now carries
        if (lpRecord.getInvestorType() != null && !lpRecord.getInvestorType().isBlank() && (master.getInvestorType() == null || master.getInvestorType().isBlank())) master.setInvestorType(lpRecord.getInvestorType());
        if (lpRecord.getInstVsHnw()  != null && !lpRecord.getInstVsHnw().isBlank()  && (master.getInstVsHnw()  == null || master.getInstVsHnw().isBlank()))  master.setInstVsHnw(lpRecord.getInstVsHnw());
        if (lpRecord.getRegion()  != null && !lpRecord.getRegion().isBlank()  && (master.getRegion()  == null || master.getRegion().isBlank()))  master.setRegion(lpRecord.getRegion());
        if (lpRecord.getParent()  != null && !lpRecord.getParent().isBlank()  && (master.getParent()  == null || master.getParent().isBlank()))  master.setParent(lpRecord.getParent());
        master.setSpv(lpRecord.isSpv());
        master.setHighQty(lpRecord.isHighQty());
        master.setIg(lpRecord.isIg());

        // Ratings — overwrite with latest cycle values when non-blank
        if (!lpRecord.getSp().isBlank())    master.setSp(lpRecord.getSp());
        if (!lpRecord.getMdy().isBlank())   master.setMdy(lpRecord.getMdy());
        if (!lpRecord.getFitch().isBlank()) master.setFitch(lpRecord.getFitch());

        // Financial scale — overwrite with latest cycle values when non-null
        if (lpRecord.getAum()          != null) master.setAum(lpRecord.getAum());
        if (lpRecord.getNav()          != null) master.setNav(lpRecord.getNav());
        if (lpRecord.getPension()      != null) master.setPension(lpRecord.getPension());
        if (lpRecord.getPensionFunded()!= null) master.setPensionFunded(lpRecord.getPensionFunded());
    }
}
