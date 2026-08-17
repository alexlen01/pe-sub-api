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
        if (lpRecord.getUbsLpCategory()     != null && !lpRecord.getUbsLpCategory().isBlank())     master.setUbsLpCategory(lpRecord.getUbsLpCategory());
        if (lpRecord.getUbsAdvanceRate() != null) master.setUbsDefaultAdvanceRate(lpRecord.getUbsAdvanceRate());
        if (lpRecord.getUbsConcentrationLimit() != null) master.setUbsDefaultConcentrationLimit(lpRecord.getUbsConcentrationLimit());

        // Stable identity — refresh blanks with anything the facility record now carries
        if (lpRecord.getInvestorType() != null && !lpRecord.getInvestorType().isBlank() && (master.getInvestorType() == null || master.getInvestorType().isBlank())) master.setInvestorType(lpRecord.getInvestorType());
        if (lpRecord.getInstitutionalOrHnw()  != null && !lpRecord.getInstitutionalOrHnw().isBlank()  && (master.getInstitutionalOrHnw()  == null || master.getInstitutionalOrHnw().isBlank()))  master.setInstitutionalOrHnw(lpRecord.getInstitutionalOrHnw());
        if (lpRecord.getRegionLocation()  != null && !lpRecord.getRegionLocation().isBlank()  && (master.getRegionLocation()  == null || master.getRegionLocation().isBlank()))  master.setRegionLocation(lpRecord.getRegionLocation());
        if (lpRecord.getParent()  != null && !lpRecord.getParent().isBlank()  && (master.getParent()  == null || master.getParent().isBlank()))  master.setParent(lpRecord.getParent());
        master.setSpv(lpRecord.isSpv());
        master.setHighQuality(lpRecord.isHighQuality());
        master.setInvestmentGrade(lpRecord.isInvestmentGrade());

        // Ratings — overwrite with latest cycle values when non-blank
        if (!lpRecord.getSpRating().isBlank())    master.setSpRating(lpRecord.getSpRating());
        if (!lpRecord.getMoodysRating().isBlank())   master.setMoodysRating(lpRecord.getMoodysRating());
        if (!lpRecord.getFitchRating().isBlank()) master.setFitchRating(lpRecord.getFitchRating());

        // Financial scale — overwrite with latest cycle values when non-null
        if (lpRecord.getAum()          != null) master.setAum(lpRecord.getAum());
        if (lpRecord.getNav()          != null) master.setNav(lpRecord.getNav());
        if (lpRecord.getPensionAssets() != null) master.setPensionAssets(lpRecord.getPensionAssets());
        if (lpRecord.getFundingRatio()  != null) master.setFundingRatio(lpRecord.getFundingRatio());
    }
}
