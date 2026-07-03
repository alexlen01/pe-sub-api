package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.dto.CommitBbRequest;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns the single write transaction behind a Shadow BB run: upsert the submitted LP dataset,
 * compute the borrowing base, persist the snapshot, and stamp the facility's last-run time.
 *
 * <p>Previously this ran inline in {@code BbController} with no transaction boundary, so a failure
 * after the LP upsert but before the snapshot save left LP Master mutated with no snapshot. Here
 * the whole sequence commits or rolls back atomically. Audit and SSE notification stay in the
 * controller so they fire only after this transaction commits.
 */
@Service
public class ShadowBbService {

    private final FacilityRepository   facilityRepo;
    private final LpRepository         lpRepo;
    private final BbSnapshotRepository snapshotRepo;
    private final BbCalculationService calculator;
    private final LpMasterService      lpMasterService;

    public ShadowBbService(FacilityRepository facilityRepo, LpRepository lpRepo,
                           BbSnapshotRepository snapshotRepo, BbCalculationService calculator,
                           LpMasterService lpMasterService) {
        this.facilityRepo    = facilityRepo;
        this.lpRepo          = lpRepo;
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

        List<Lp> lps = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        BbResult result = calculator.compute(lps, facility.getConcLimitM().doubleValue());

        BbSnapshot snapshot = new BbSnapshot();
        snapshot.setFacilityId(facilityId);
        snapshot.setResult(result);
        BbSnapshot saved = snapshotRepo.save(snapshot);

        facility.setLastRunAt(LocalDateTime.now());
        facilityRepo.save(facility);

        return new RunResult(saved, facility.getName(), lps.size());
    }
}
