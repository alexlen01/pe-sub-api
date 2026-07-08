package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.dto.CommitBbRequest;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Owns the single write transaction behind a Shadow BB run: upsert the submitted LP Dataset,
 * compute the borrowing base, persist the snapshot, and stamp the facility's last-run time.
 *
 * <p>Previously this ran inline in {@code BbController} with no transaction boundary, so a failure
 * after the LpRecord upsert but before the snapshot save left LP Master mutated with no snapshot. Here
 * the whole sequence commits or rolls back atomically. Audit and SSE notification stay in the
 * controller so they fire only after this transaction commits.
 */
@Service
public class ShadowBbService {

    private final FacilityRepository   facilityRepo;
    private final LpRecordRepository         lpRecordRepo;
    private final BbSnapshotRepository snapshotRepo;
    private final BbCalculationService calculator;
    private final LpMasterService      lpMasterService;

    public ShadowBbService(FacilityRepository facilityRepo, LpRecordRepository lpRecordRepo,
                           BbSnapshotRepository snapshotRepo, BbCalculationService calculator,
                           LpMasterService lpMasterService) {
        this.facilityRepo    = facilityRepo;
        this.lpRecordRepo          = lpRecordRepo;
        this.snapshotRepo    = snapshotRepo;
        this.calculator      = calculator;
        this.lpMasterService = lpMasterService;
    }

    /** The outcome of a run, carrying everything the controller needs for audit + notification. */
    public record RunResult(BbSnapshot snapshot, String facilityName, int lpCount) {}

    @Transactional
    public RunResult runAndSnapshot(int facilityId, CommitBbRequest request) {
        Facility facility = facilityRepo.findById(facilityId)
            .orElseThrow(() -> new ResourceNotFoundException("Facility not found: " + facilityId));

        if (request != null && request.lps() != null && !request.lps().isEmpty()) {
            lpMasterService.upsertAll(facilityId, request.lps());
        }

        List<LpRecord> lps = lpRecordRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        refreshRanks(lps);
        lps = lpRecordRepo.saveAll(lps);
        BbResult result = calculator.compute(lps, facility.getConcLimitM().doubleValue());

        BbSnapshot snapshot = new BbSnapshot();
        snapshot.setFacilityId(facilityId);
        snapshot.setResult(result);
        BbSnapshot saved = snapshotRepo.save(snapshot);

        facility.setLastRunAt(LocalDateTime.now());
        facilityRepo.save(facility);

        return new RunResult(saved, facility.getName(), lps.size());
    }

    private void refreshRanks(List<LpRecord> lps) {
        List<LpRecord> rankable = lps.stream()
            .filter(ShadowBbService::isRankable)
            .sorted(Comparator
                .comparingDouble((LpRecord lpRecord) -> BbCalculationService.moneyM(lpRecord.getUcNum(), lpRecord.getUc())).reversed()
                .thenComparing(lpRecord -> lpRecord.getInvestorName() == null ? "" : lpRecord.getInvestorName()))
            .toList();

        Map<Integer, Integer> ranksById = competitionRanks(rankable);
        for (LpRecord lpRecord : lps) {
            lpRecord.setRank(lpRecord.getId() == null ? null : ranksById.get(lpRecord.getId()));
        }
    }

    private static boolean isRankable(LpRecord lpRecord) {
        String cls = lpRecord.getCls();
        String agentCls = lpRecord.getAgentCls() == null ? "" : lpRecord.getAgentCls().trim();
        return lpRecord.isInc()
            && !"Excluded".equals(cls)
            && !agentCls.toLowerCase().startsWith("ineligible investor");
    }

    private static Map<Integer, Integer> competitionRanks(List<LpRecord> rankable) {
        AtomicInteger position = new AtomicInteger(0);
        class RankedValue {
            private double previousValue = Double.NaN;
            private int currentRank = 0;
        }
        RankedValue state = new RankedValue();
        return rankable.stream().collect(Collectors.toMap(
            LpRecord::getId,
            lpRecord -> {
                int index = position.incrementAndGet();
                double value = BbCalculationService.moneyM(lpRecord.getUcNum(), lpRecord.getUc());
                if (index == 1 || Double.compare(value, state.previousValue) != 0) {
                    state.currentRank = index;
                    state.previousValue = value;
                }
                return state.currentRank;
            }
        ));
    }
}
