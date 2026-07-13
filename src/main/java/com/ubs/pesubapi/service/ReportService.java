package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.*;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.ReportHistory;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.ReportHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/** Builds the Reports screen payloads from persisted BB snapshots and facilities,
 *  and records/reads report-generation history. */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** Fixed certificate ordering for the current UBS LP Classification tiers. */
    private static final List<String> TIER_ORDER =
        List.of(
            "Rated Investor",
            "Unrated NAV > $1Bn",
            "FoF & Other > $10Bn AUM",
            "Corp Pension > $5Bn Assets",
            "Other Institutional",
            "Excluded"
        );

    private final BbSnapshotRepository    snapshotRepo;
    private final FacilityRepository      facilityRepo;
    private final ReportHistoryRepository historyRepo;

    public ReportService(BbSnapshotRepository snapshotRepo, FacilityRepository facilityRepo,
                         ReportHistoryRepository historyRepo) {
        this.snapshotRepo = snapshotRepo;
        this.facilityRepo = facilityRepo;
        this.historyRepo  = historyRepo;
    }

    // ── Collateral Market Value & Coverage (BB Certificate) ──────────────────────

    public CollateralReportDto collateral(int facilityId, Integer snapshotId) {
        Facility facility = requireFacility(facilityId);
        BbSnapshot snap = snapshotId != null
            ? snapshotRepo.findById(snapshotId)
                .filter(s -> s.getFacilityId() == facilityId)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Snapshot " + snapshotId + " not found for facility " + facilityId))
            : requireLatestSnapshot(facilityId);
        log.info("collateral_report_selected_snapshot facilityId={} requestedSnapshotId={} resolvedSnapshotId={} calculatedAt={}",
            facilityId, snapshotId, snap.getId(), snap.getCalculatedAt());

        BbResult result = snap.getResult();
        List<ComputedLpRecord> lps = result != null && result.lps() != null ? result.lps() : List.of();
        BbSummary snapshotSummary = result != null ? result.summary() : null;
        boolean usedSummaryFallback = snapshotSummary == null;
        BbSummary summary = usedSummaryFallback
            ? new BbSummary(0, 0, 0, 0, 0, 0, 0, 0)
            : snapshotSummary;
        if (usedSummaryFallback) {
            log.warn("collateral_report_summary_fallback facilityId={} snapshotId={} reason={}",
                facilityId, snap.getId(), result == null ? "missing_result" : "missing_summary");
        }

        Map<String, double[]> agg = new LinkedHashMap<>();          // [count, uncalledM, ubbM]
        Map<String, String>   rates = new HashMap<>();
        for (String tier : TIER_ORDER) agg.put(tier, new double[3]);
        for (ComputedLpRecord lpRecord : lps) {
            String cls = lpRecord.cls() != null && !lpRecord.cls().isBlank() ? lpRecord.cls() : "Unclassified";
            double[] a = agg.computeIfAbsent(cls, k -> new double[3]);
            a[0]++;
            a[1] += BbCalculationService.parseMoney(lpRecord.uc());
            a[2] += lpRecord.ubbM();
            rates.putIfAbsent(cls, lpRecord.rate());
        }

        List<CollateralReportDto.ClassBreakdownRow> breakdown = agg.entrySet().stream()
            .filter(e -> e.getValue()[0] > 0)
            .map(e -> new CollateralReportDto.ClassBreakdownRow(
                e.getKey(), (int) e.getValue()[0], e.getValue()[1], e.getValue()[2],
                rates.getOrDefault(e.getKey(), "0%")))
            .toList();

        double totalEligibleUncalledM = lps.stream().mapToDouble(lp -> lp != null ? lp.uecM() : 0).sum();
        log.info("collateral_report_ready facilityId={} snapshotId={} lpCount={} classRows={} totalEligibleUncalledM={}",
            facilityId, snap.getId(), lps.size(), breakdown.size(), totalEligibleUncalledM);

        return new CollateralReportDto(
            facilityId, facility.getName(), facility.getAgentBank(),
            snap.getId(), snap.getCalculatedAt(), summary,
            totalEligibleUncalledM, breakdown);
    }

    // ── Effective Advance Rates trend ─────────────────────────────────────────────

    public List<EarPointDto> earTrend(int facilityId) {
        requireFacility(facilityId);
        return snapshotRepo.findByFacilityIdOrderByCalculatedAtAsc(facilityId).stream()
            .filter(s -> s.getResult() != null && s.getResult().summary() != null)
            .map(s -> {
                BbSummary sum = s.getResult().summary();
                return new EarPointDto(s.getCalculatedAt(), sum.ear(), sum.agentEar(), sum.earDelta());
            })
            .toList();
    }

    // ── Agent Bank Exposure ───────────────────────────────────────────────────────

    public List<AgentBankExposureDto> agentBankExposure() {
        Map<Integer, BbSnapshot> latestByFacility = snapshotRepo.findLatestPerFacility().stream()
            .collect(Collectors.toMap(s -> s.getFacilityId(), s -> s));

        Map<String, double[]> agg = new LinkedHashMap<>();          // [facilities, lps, ubsBB, agentBB]
        for (Facility f : facilityRepo.findAll()) {
            double[] a = agg.computeIfAbsent(f.getAgentBank(), k -> new double[4]);
            a[0]++;
            BbSnapshot snap = latestByFacility.get(f.getId());
            if (snap == null || snap.getResult() == null) continue;
            a[1] += snap.getResult().lps() != null ? snap.getResult().lps().size() : 0;
            BbSummary sum = snap.getResult().summary();
            if (sum != null) {
                a[2] += sum.totalUBB();
                a[3] += sum.totalABB();
            }
        }

        return agg.entrySet().stream()
            .map(e -> new AgentBankExposureDto(
                e.getKey(), (int) e.getValue()[0], (int) e.getValue()[1],
                e.getValue()[2], e.getValue()[3], e.getValue()[2] - e.getValue()[3]))
            .sorted(Comparator.comparingDouble((AgentBankExposureDto dto) -> dto != null ? dto.ubsBBM() : 0).reversed())
            .toList();
    }

    // ── Concentration Exposures ───────────────────────────────────────────────────

    public List<BbBreach> concentrationBreaches(int facilityId) {
        requireFacility(facilityId);
        return snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .map(s -> s.getResult())
            .map(r -> r != null ? r.breaches() : java.util.List.<BbBreach>of())
            .orElseGet(java.util.List::of);
    }

    // ── Report history ────────────────────────────────────────────────────────────

    public List<ReportHistoryDto> history() {
        return historyRepo.findTop50ByOrderByCreatedAtDesc().stream()
            .map(ReportHistoryDto::from)
            .toList();
    }

    public ReportHistoryDto recordHistory(CreateReportHistoryRequest req, String userName) {
        if (req == null || req.report() == null || req.report().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "report is required");

        ReportHistory entry = new ReportHistory();
        entry.setReport(req.report().trim());
        entry.setSnapshotLabel(req.snapshotLabel());
        entry.setFormat(req.format());
        entry.setUserName(userName);
        if (req.facilityId() != null) {
            Facility facility = requireFacility(req.facilityId());
            entry.setFacilityId(facility.getId());
            entry.setFacilityName(facility.getName());
        }
        return ReportHistoryDto.from(historyRepo.save(entry));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private Facility requireFacility(int facilityId) {
        return facilityRepo.findById(facilityId)
            .orElseThrow(() -> new ResourceNotFoundException("Facility not found: " + facilityId));
    }

    private BbSnapshot requireLatestSnapshot(int facilityId) {
        return snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No BB snapshot exists for facility " + facilityId));
    }
}

