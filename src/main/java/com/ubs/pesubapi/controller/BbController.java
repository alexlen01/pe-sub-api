package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.BbSnapshotDto;
import com.ubs.pesubapi.dto.CommitBbRequest;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.BbCalculationService;
import com.ubs.pesubapi.service.NotificationService;
import com.ubs.pesubapi.service.ShadowBbService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bb")
public class BbController {

    private static final Logger log = LoggerFactory.getLogger(BbController.class);

    private final FacilityRepository    facilityRepo;
    private final LpRecordRepository          lpRecordRepo;
    private final BbSnapshotRepository  snapshotRepo;
    private final BbCalculationService  calculator;
    private final ShadowBbService       shadowBbService;
    private final NotificationService   notifier;
    private final AuditLogService       auditService;
    private final CurrentUserService    currentUser;

    public BbController(FacilityRepository facilityRepo, LpRecordRepository lpRecordRepo,
                        BbSnapshotRepository snapshotRepo, BbCalculationService calculator,
                        ShadowBbService shadowBbService, NotificationService notifier,
                        AuditLogService auditService, CurrentUserService currentUser) {
        this.facilityRepo    = facilityRepo;
        this.lpRecordRepo          = lpRecordRepo;
        this.snapshotRepo    = snapshotRepo;
        this.calculator      = calculator;
        this.shadowBbService = shadowBbService;
        this.notifier        = notifier;
        this.auditService    = auditService;
        this.currentUser     = currentUser;
    }

    /**
     * Accepts a full LP Dataset from the Run Shadow BB wizard, upserts all LP records into
     * LP Master, computes the Shadow BB, and saves a snapshot. This is the single write
     * operation that materialises Steps 3a–3d from BB_PROCESS_FLOW.md in one transaction.
     */
    @PostMapping("/run/{facilityId}")
    public ResponseEntity<BbSnapshotDto> run(
            @PathVariable int facilityId,
            @RequestBody(required = false) CommitBbRequest request,
            HttpServletRequest httpRequest) {

        // Upsert + compute + snapshot + facility stamp run in one transaction inside the service.
        ShadowBbService.RunResult run = shadowBbService.runAndSnapshot(facilityId, request);
        BbSnapshot saved = run.snapshot();
        var summary = saved.getResult().summary();
        log.info("Shadow BB calculated facilityId={} snapshotId={} lpCount={} totalABB={} totalUBB={} breaches={}",
            facilityId, saved.getId(), run.lpCount(), summary.totalABB(), summary.totalUBB(),
            saved.getResult().breaches() != null ? saved.getResult().breaches().size() : 0);

        // Audit + notify only after the transaction has committed, so neither fires on rollback.
        String detail = run.lpCount() + " LPs · UBS BB $" + String.format("%.1f", summary.totalUBB()) + "M";
        notifier.broadcast("Shadow BB calculated for " + run.facilityName()
            + " — UBS BB $" + String.format("%.1f", summary.totalUBB()) + "M");
        auditService.log("BB Recalculated", detail, facilityId, currentUser.uuName(), currentUser.auditDisplayName(),
            auditService.extractIp(httpRequest));

        return ResponseEntity.status(201).body(BbSnapshotDto.from(saved));
    }

    @GetMapping("/snapshots/{facilityId}")
    public List<BbSnapshotDto> snapshots(@PathVariable int facilityId) {
        return snapshotRepo.findByFacilityIdOrderByCalculatedAtAsc(facilityId)
            .stream().map(BbSnapshotDto::from).toList();
    }

    @GetMapping("/snapshots/{facilityId}/latest")
    public ResponseEntity<BbSnapshotDto> latestSnapshot(@PathVariable int facilityId) {
        return snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .map(BbSnapshotDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    // ── Portfolio & BB Summary — 5 tables ────────────────────────────────────────

    @GetMapping("/summary-ext/{facilityId}")
    public ResponseEntity<Map<String, Object>> summaryExt(@PathVariable int facilityId) {
        List<com.ubs.pesubapi.entity.LpRecord> lps = lpRecordRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        if (lps.isEmpty()) return ResponseEntity.ok(emptySummaryExt());

        int    totalLPs       = lps.size();
        double totalCapCommit = lps.stream().mapToDouble(lpRecord -> capCommitM(lpRecord)).sum();
        // Called Capital is a calculated column (SHADOW_BB_ANALYSIS): Capital Commitments − Uncalled
        // Capital. Most LPs carry no stored called_cap, so derive it per-LP rather than summing a
        // column of blanks (which previously yielded $0).
        double totalCalledCap = lps.stream().mapToDouble(BbController::calledCapM).sum();
        double totalUncalled  = lps.stream().mapToDouble(lpRecord -> ucM(lpRecord)).sum();
        double pctCalled      = totalCapCommit > 0 ? totalCalledCap / totalCapCommit : 0;

        // Uncalled-weighted population shares (SHADOW_BB_ANALYSIS Table 1):
        // each metric = Σ(uncalled of matching LPs) ÷ Σ(total uncalled), not a headcount ratio.
        double instUncalled   = lps.stream().filter(lpRecord -> "Institutional".equals(lpRecord.getInstVsHnw())).mapToDouble(lpRecord -> ucM(lpRecord)).sum();
        double hnwUncalled    = lps.stream().filter(lpRecord -> "HNW".equals(lpRecord.getInstVsHnw())).mapToDouble(lpRecord -> ucM(lpRecord)).sum();
        double igUncalled     = lps.stream().filter(lpRecord -> lpRecord.isIg()).mapToDouble(lpRecord -> ucM(lpRecord)).sum();
        double pctInstitutional = totalUncalled > 0 ? instUncalled / totalUncalled : 0;
        double pctHNW           = totalUncalled > 0 ? hnwUncalled  / totalUncalled : 0;
        double igRatio          = totalUncalled > 0 ? igUncalled   / totalUncalled : 0;

        List<Double> sortedUc = lps.stream()
            .mapToDouble(lpRecord -> ucM(lpRecord))
            .boxed().sorted(Comparator.reverseOrder()).toList();
        double top10Uc        = sortedUc.stream().limit(10).mapToDouble(d -> d).sum();
        double top20Uc        = sortedUc.stream().limit(20).mapToDouble(d -> d).sum();
        double pctTop10       = totalUncalled > 0 ? top10Uc / totalUncalled : 0;
        double pctTop20       = totalUncalled > 0 ? top20Uc / totalUncalled : 0;
        // % of Uncalled Capital from LPs with AUM > $25bn (parseMoney returns $millions, so $25bn = 25_000).
        double gt25bnUncalled       = lps.stream().filter(lpRecord -> aumM(lpRecord) > 25_000).mapToDouble(lpRecord -> ucM(lpRecord)).sum();
        double pctUncalledGt25bnAum = totalUncalled > 0 ? gt25bnUncalled / totalUncalled : 0;

        double agentBBRaw = 0, ubsBBRaw = 0;
        Optional<BbSnapshot> latest = snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId);
        if (latest.isPresent() && latest.get().getResult() != null) {
            agentBBRaw = latest.get().getResult().summary().totalABB();
            ubsBBRaw   = latest.get().getResult().summary().totalUBB();
        }
        double ubsAdvRate = totalUncalled > 0 ? ubsBBRaw / totalUncalled : 0;

        // ── Facility-level inputs & derived metrics (SHADOW_BB_ANALYSIS Table 2) ──────
        // All monetary fields in this response are expressed in $millions.
        // facilitySize: use the explicit facility_size override when set; otherwise fall back to
        // loan_amount (the committed facility size stored in the Agent Bank Summary).
        Facility facility = facilityRepo.findById(facilityId).orElse(null);
        java.math.BigDecimal rawSize = facility != null
            ? (facility.getFacilitySize() != null ? facility.getFacilitySize() : facility.getLoanAmount())
            : null;
        double facilitySizeM = rawSize != null ? rawSize.doubleValue() / 1_000_000.0 : 0;
        double ubsParticipationM = facility != null && facility.getUbsParticipation() != null
            ? facility.getUbsParticipation().doubleValue() / 1_000_000.0 : 0;
        double ubsParticipationPct = facilitySizeM > 0 ? ubsParticipationM / facilitySizeM : 0;
        double facilityLTV  = totalUncalled > 0 ? facilitySizeM / totalUncalled : 0;          // Size ÷ Total Uncalled
        double availableCommit = Math.min(facilitySizeM, agentBBRaw);                          // MIN(Size, Agent BB)
        double facilityAdvRate = totalUncalled > 0 ? agentBBRaw / totalUncalled : 0;           // Agent BB ÷ Total Uncalled

        // ── Table 1: LP Portfolio & Table 2: Borrowing Base (scalar fields above) ──

        // ── Table 3: BUSA breakdown — group by UBS advance rate ──────────────────
        Map<String, double[]> busaMap = new LinkedHashMap<>();
        for (String key : List.of("90%", "75%", "65%", "50%", "0%"))
            busaMap.put(key, new double[]{0, 0});   // [count, dollars]
        for (var lpRecord : lps) {
            double rate = calculator.advanceRateFraction(lpRecord);
            String rateKey = String.format("%.0f%%", rate * 100);
            busaMap.computeIfAbsent(rateKey, k -> new double[]{0, 0});
            busaMap.get(rateKey)[0]++;
            busaMap.get(rateKey)[1] += ucM(lpRecord);
        }
        List<Map<String, Object>> busaBreakdown = busaMap.entrySet().stream()
            .map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rate",    e.getKey());
                row.put("count",   (int) e.getValue()[0]);
                row.put("dollars", e.getValue()[1]);
                row.put("pct",     totalUncalled > 0 ? e.getValue()[1] / totalUncalled : 0);
                return row;
            }).collect(Collectors.toList());

        // ── Table 4: Agent breakdown — group by agent rate ────────────────────────
        Map<String, double[]> agentMap = new LinkedHashMap<>();
        for (String key : List.of("90%", "75%", "65%", "50%", "0%"))
            agentMap.put(key, new double[]{0, 0});   // [count, dollars]
        for (var lpRecord : lps) {
            String rateKey = normalizeAgentSummaryRate(lpRecord.getAgentRate());
            agentMap.computeIfAbsent(rateKey, k -> new double[]{0, 0});
            agentMap.get(rateKey)[0]++;
            agentMap.get(rateKey)[1] += ucM(lpRecord);
        }
        List<Map<String, Object>> agentBreakdown = agentMap.entrySet().stream()
            .sorted((a, b) -> {
                double va = parseRatePct(a.getKey()), vb = parseRatePct(b.getKey());
                return Double.compare(vb, va);
            })
            .map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rate",    e.getKey());
                row.put("count",   (int) e.getValue()[0]);
                row.put("dollars", e.getValue()[1]);
                row.put("pct",     totalUncalled > 0 ? e.getValue()[1] / totalUncalled : 0);
                return row;
            }).collect(Collectors.toList());

        // ── Table 5: LP Category breakdown ───────────────────────────────────────
        // Roll the granular UBS LP Category labels up into the four canonical eligibility
        // buckets (SHADOW_BB_ANALYSIS Table 5). Order is fixed so the table reads consistently.
        Map<String, double[]> clsMap = new LinkedHashMap<>();
        for (String key : List.of("Rated Investors", "Unrated Investors", "Eligible Investors", "Excluded Investors"))
            clsMap.put(key, new double[]{0, 0});
        for (var lpRecord : lps) {
            String bucket = canonicalClassBucket(lpRecord.getCls());
            clsMap.get(bucket)[0]++;
            clsMap.get(bucket)[1] += ucM(lpRecord);
        }
        List<Map<String, Object>> clsBreakdown = clsMap.entrySet().stream()
            .filter(e -> e.getValue()[0] > 0)
            .map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label",   e.getKey());
                row.put("count",   (int) e.getValue()[0]);
                row.put("dollars", e.getValue()[1]);
                row.put("pct",     totalUncalled > 0 ? e.getValue()[1] / totalUncalled : 0);
                return row;
            }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCapCommit",      totalCapCommit);
        out.put("totalCalledCap",      totalCalledCap);
        out.put("pctCalled",           pctCalled);
        out.put("totalAllUncalled",    totalUncalled);
        out.put("totalLPs",            totalLPs);
        out.put("pctInstitutional",    pctInstitutional);
        out.put("pctHNW",              pctHNW);
        out.put("pctTop10",            pctTop10);
        out.put("pctTop20",            pctTop20);
        out.put("igRatio",             igRatio);
        out.put("pctUncalledGt25bnAum", pctUncalledGt25bnAum);
        out.put("facilitySize",        facilitySizeM);
        out.put("ubsParticipation",    ubsParticipationM);
        out.put("ubsParticipationPct", ubsParticipationPct);
        out.put("facilityLTV",         facilityLTV);
        out.put("availableCommit",     availableCommit);
        out.put("facilityAdvRate",     facilityAdvRate);
        out.put("agentBBRaw",          agentBBRaw);
        out.put("ubsBBRaw",            ubsBBRaw);
        out.put("ubsAdvRate",          ubsAdvRate);
        out.put("busaBreakdown",       busaBreakdown);
        out.put("agentBreakdown",      agentBreakdown);
        out.put("clsBreakdown",        clsBreakdown);
        return ResponseEntity.ok(out);
    }

    /** Called Capital for one LpRecord ($millions): the stored value when present, else the calculated
     *  Capital Commitments − Uncalled Capital (never negative). */
    private static double calledCapM(com.ubs.pesubapi.entity.LpRecord lpRecord) {
        if (lpRecord.getCalledCap() != null && !lpRecord.getCalledCap().isBlank()) return parseMoney(lpRecord.getCalledCap());
        return Math.max(0, capCommitM(lpRecord) - ucM(lpRecord));
    }

    // Single source of truth for money parsing — a private near-copy previously lived here
    // and had already drifted from the engine's version (no N/A or en-dash handling).
    private static double parseMoney(String value) {
        return BbCalculationService.parseMoney(value);
    }

    // Numeric-first money reads ($millions) — the precise C2 numeric column when present, else the
    // legacy display string. Keeps summaryExt aligned with the engine's computeOne.
    private static double ucM(com.ubs.pesubapi.entity.LpRecord lpRecord)        { return BbCalculationService.moneyM(lpRecord.getUcNum(), lpRecord.getUc()); }
    private static double capCommitM(com.ubs.pesubapi.entity.LpRecord lpRecord) { return BbCalculationService.moneyM(lpRecord.getCapCommitNum(), lpRecord.getCapCommit()); }
    private static double aumM(com.ubs.pesubapi.entity.LpRecord lpRecord)       { return BbCalculationService.moneyM(lpRecord.getAumNum(), lpRecord.getAum()); }

    /** Rolls a granular LP Classification (UBS or legacy taxonomy) up into one of the four
     *  canonical eligibility buckets used by SHADOW_BB_ANALYSIS Table 5. */
    private static String canonicalClassBucket(String cls) {
        if (cls == null || cls.isBlank()) return "Excluded Investors";
        return switch (cls) {
            case "Rated Investor", "Rated" -> "Rated Investors";
            case "FoF & Other > $10Bn AUM", "Corp Pension > $5Bn Assets", "Corp Pension > $1Bn Assets",
                 "Unrated NAV > $1Bn", "Unrated >2bn", "Unrated 1–2bn" -> "Unrated Investors";
            case "Other Institutional", "Eligible",
                 "HNW Feeder (acceptable)", "HNW (acceptable)", "Included (PWM)" -> "Eligible Investors";
            case "Excluded" -> "Excluded Investors";
            default -> "Eligible Investors";
        };
    }

    private static double parseRatePct(String rate) {
        if (rate == null) return 0;
        try { return Double.parseDouble(rate.replace("%", "").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String normalizeAgentSummaryRate(String rate) {
        double value = parseRatePct(rate);
        if (value == 90 || value == 95) return "90%";
        if (value >= 70 && value <= 80) return "75%";
        if (value == 60 || value == 65) return "65%";
        if (value >= 20 && value <= 55) return "50%";
        return "0%";
    }

    private Map<String, Object> emptySummaryExt() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("totalCapCommit", "totalCalledCap", "pctCalled", "totalAllUncalled",
                "totalLPs", "pctInstitutional", "pctHNW", "pctTop10", "pctTop20", "igRatio",
                "pctUncalledGt25bnAum", "facilitySize", "ubsParticipation", "ubsParticipationPct",
                "facilityLTV", "availableCommit", "facilityAdvRate", "agentBBRaw", "ubsBBRaw",
                "ubsAdvRate")) {
            out.put(k, 0.0);
        }
        out.put("busaBreakdown",  List.of());
        out.put("agentBreakdown", List.of());
        out.put("clsBreakdown",   List.of());
        return out;
    }
}
