package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.dto.CommitBbRequest;
import com.ubs.pesubapi.dto.ComputedLpRecord;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final LpMasterWriteBackService lpMasterWriteBack;
    private final MatchQueueEntryRepository matchQueueRepo;

    public ShadowBbService(FacilityRepository facilityRepo, LpRecordRepository lpRecordRepo,
                           BbSnapshotRepository snapshotRepo, BbCalculationService calculator,
                           LpMasterService lpMasterService, LpMasterWriteBackService lpMasterWriteBack,
                           MatchQueueEntryRepository matchQueueRepo) {
        this.facilityRepo    = facilityRepo;
        this.lpRecordRepo          = lpRecordRepo;
        this.snapshotRepo    = snapshotRepo;
        this.calculator      = calculator;
        this.lpMasterService = lpMasterService;
        this.lpMasterWriteBack = lpMasterWriteBack;
        this.matchQueueRepo  = matchQueueRepo;
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
        writeBackComputedValues(lps, result);
        lpRecordRepo.saveAll(lps);

        BbSnapshot snapshot = new BbSnapshot();
        snapshot.setFacilityId(facilityId);
        snapshot.setResult(result);
        BbSnapshot saved = snapshotRepo.save(snapshot);

        facility.setLastRunAt(LocalDateTime.now());
        facilityRepo.save(facility);

        // Sync the settled facility LP records back to the bank-wide LP Master so a Run *and* a
        // Re-Run (which carries no rows and only recomputes) both refresh LP Master's UBS credit
        // profile — not only the wizard's separate /complete step. Same transaction; idempotent.
        lpMasterWriteBack.writeBack(facilityId);

        return new RunResult(saved, facility.getName(), lps.size());
    }

    /**
     * Persists the engine's per-LP results onto the facility LP records in the run transaction,
     * so the stored rows always match the snapshot regardless of what (if anything) the client
     * posted. {@code abb}/{@code abbNum} are deliberately left untouched: a stored Agent BB is an
     * agent-reported figure (written at ingest) and writing the derived value back would make
     * {@code hasStoredAbb} freeze derivation against future rate edits.
     */
    private static void writeBackComputedValues(List<LpRecord> lps, BbResult result) {
        Map<Integer, ComputedLpRecord> byId = result.lps().stream()
            .filter(row -> row.id() != null)
            .collect(Collectors.toMap(row -> row.id(), row -> row));
        for (LpRecord lpRecord : lps) {
            ComputedLpRecord row = lpRecord.getId() != null ? byId.get(lpRecord.getId()) : null;
            if (row == null) continue;
            // The engine carries these in $millions at full precision; the columns are NUMERIC
            // absolute dollars, so persist the exact figure rather than a rounded "$17.7M".
            lpRecord.setUbsBorrowingBase(dollarsFromM(row.ubbM()));
            lpRecord.setUbsExcessConcentration(dollarsFromM(row.concExcessM()));
            lpRecord.setAgentExcessConcentration(dollarsFromM(row.agentExcessM()));
        }
    }

    private static BigDecimal dollarsFromM(double millions) {
        return BigDecimal.valueOf(millions * 1_000_000.0).setScale(2, RoundingMode.HALF_UP);
    }

    /** Outcome of a facility LP record deletion, for audit/notification messaging. */
    public record LpRecordDeletion(int facilityId, String investorName) {}

    /**
     * Hard-deletes a single facility LP record — the manual correction path for a row that
     * slipped through extraction review. Match-queue entries pointing at the record are detached
     * (decision history kept), lp_rates rows cascade in the schema, and the facility's ranks are
     * recomputed over the remaining population in the same transaction so the persisted Rank
     * column never carries the deleted LP. The current BB snapshot is untouched — totals refresh
     * on the next Run / Re-run Shadow BB.
     */
    @Transactional
    public LpRecordDeletion deleteLpRecord(int lpRecordId) {
        LpRecord record = lpRecordRepo.findById(lpRecordId)
            .orElseThrow(() -> new ResourceNotFoundException("LP record " + lpRecordId + " not found"));
        int facilityId = record.getFacilityId();
        matchQueueRepo.clearMatchedLpRef(lpRecordId);
        lpRecordRepo.delete(record);
        lpRecordRepo.flush();   // the rank pass below must see the post-delete population

        List<LpRecord> remaining = lpRecordRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        refreshRanks(remaining);
        lpRecordRepo.saveAll(remaining);
        return new LpRecordDeletion(facilityId, record.getInvestorName());
    }

    // Ranks are computed here only — the UI never derives them — and every LP record is ranked
    // by uncalled capital, Excluded / not-included LPs too, so the Rank column reflects the LP's
    // size position within the full facility population.
    private void refreshRanks(List<LpRecord> lps) {
        List<LpRecord> ordered = lps.stream()
            .sorted(Comparator
                .comparingDouble((LpRecord lpRecord) -> BbCalculationService.dollarM(lpRecord.getUncalledCapital())).reversed()
                .thenComparing(lpRecord -> lpRecord.getInvestorName() == null ? "" : lpRecord.getInvestorName()))
            .toList();

        Map<Integer, Integer> ranksById = competitionRanks(ordered);
        for (LpRecord lpRecord : lps) {
            lpRecord.setLpRank(lpRecord.getId() == null ? null : ranksById.get(lpRecord.getId()));
        }
    }

    private static Map<Integer, Integer> competitionRanks(List<LpRecord> rankable) {
        AtomicInteger position = new AtomicInteger(0);
        class RankedValue {
            private double previousValue = Double.NaN;
            private int currentRank = 0;
        }
        RankedValue state = new RankedValue();
        return rankable.stream().collect(Collectors.toMap(
            lp -> lp.getId(),
            lpRecord -> {
                int index = position.incrementAndGet();
                double value = BbCalculationService.dollarM(lpRecord.getUncalledCapital());
                if (index == 1 || Double.compare(value, state.previousValue) != 0) {
                    state.currentRank = index;
                    state.previousValue = value;
                }
                return state.currentRank;
            }
        ));
    }
}
