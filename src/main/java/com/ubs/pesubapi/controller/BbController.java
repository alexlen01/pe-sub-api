package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.dto.CommitBbRequest;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.BbCalculationService;
import com.ubs.pesubapi.service.LpMasterService;
import com.ubs.pesubapi.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bb")
public class BbController {

    private static final Logger log = LoggerFactory.getLogger(BbController.class);

    private final FacilityRepository    facilityRepo;
    private final LpRepository          lpRepo;
    private final BbSnapshotRepository  snapshotRepo;
    private final BbCalculationService  calculator;
    private final LpMasterService       lpMasterService;
    private final NotificationService   notifier;
    private final AuditLogService       auditService;

    public BbController(FacilityRepository facilityRepo, LpRepository lpRepo,
                        BbSnapshotRepository snapshotRepo, BbCalculationService calculator,
                        LpMasterService lpMasterService, NotificationService notifier,
                        AuditLogService auditService) {
        this.facilityRepo    = facilityRepo;
        this.lpRepo          = lpRepo;
        this.snapshotRepo    = snapshotRepo;
        this.calculator      = calculator;
        this.lpMasterService = lpMasterService;
        this.notifier        = notifier;
        this.auditService    = auditService;
    }

    /**
     * Accepts a full LP dataset from the Run Shadow BB wizard, upserts all LP records into
     * LP Master, computes the Shadow BB, and saves a snapshot. This is the single write
     * operation that materialises Steps 3a–3d from BB_PROCESS_FLOW.md in one transaction.
     */
    @PostMapping("/run/{facilityId}")
    public ResponseEntity<BbSnapshot> run(
            @PathVariable int facilityId,
            @RequestBody(required = false) CommitBbRequest request,
            HttpServletRequest httpRequest) {

        Facility facility = facilityRepo.findById(facilityId)
            .orElseThrow(() -> new ResourceNotFoundException("Facility not found: " + facilityId));

        if (request != null && request.lps() != null && !request.lps().isEmpty()) {
            lpMasterService.upsertAll(facilityId, request.lps());
            log.info("Shadow BB request upserted LP master rows facilityId={} rows={}", facilityId, request.lps().size());
        }

        List<com.ubs.pesubapi.entity.Lp> lps = lpRepo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        BbResult result = calculator.compute(lps, facility.getConcLimitM().doubleValue());

        BbSnapshot snapshot = new BbSnapshot();
        snapshot.setFacilityId(facilityId);
        snapshot.setResult(result);
        BbSnapshot saved = snapshotRepo.save(snapshot);
        log.info("Shadow BB calculated facilityId={} snapshotId={} lpCount={} totalABB={} totalUBB={} breaches={}",
            facilityId, saved.getId(), lps.size(), result.summary().totalABB(), result.summary().totalUBB(),
            result.breaches() != null ? result.breaches().size() : 0);

        facility.setLastRunAt(LocalDateTime.now());
        facilityRepo.save(facility);

        String detail = lps.size() + " LPs · UBS BB $"
            + String.format("%.1f", result.summary().totalUBB()) + "M";
        notifier.broadcast("Shadow BB calculated for " + facility.getName()
            + " — UBS BB $" + String.format("%.1f", result.summary().totalUBB()) + "M");
        auditService.log("BB Recalculated", detail, facilityId, "J. Smith",
            auditService.extractIp(httpRequest));

        return ResponseEntity.status(201).body(saved);
    }

    @GetMapping("/snapshots/{facilityId}")
    public List<BbSnapshot> snapshots(@PathVariable int facilityId) {
        return snapshotRepo.findByFacilityIdOrderByCalculatedAtAsc(facilityId);
    }

    @GetMapping("/snapshots/{facilityId}/latest")
    public ResponseEntity<BbSnapshot> latestSnapshot(@PathVariable int facilityId) {
        return snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    // ── Portfolio & BB Summary — 5 tables ────────────────────────────────────────

    @GetMapping("/summary-ext/{facilityId}")
    public ResponseEntity<Map<String, Object>> summaryExt(@PathVariable int facilityId) {
        List<com.ubs.pesubapi.entity.Lp> lps = lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId);
        if (lps.isEmpty()) return ResponseEntity.ok(emptySummaryExt());

        int    totalLPs       = lps.size();
        double totalCapCommit = lps.stream().mapToDouble(lp -> parseMoney(lp.getCapCommit())).sum();
        // Called Capital is a calculated column (SHADOW_BB_ANALYSIS): Capital Commitments − Uncalled
        // Capital. Most LPs carry no stored called_cap, so derive it per-LP rather than summing a
        // column of blanks (which previously yielded $0).
        double totalCalledCap = lps.stream().mapToDouble(BbController::calledCapM).sum();
        double totalUncalled  = lps.stream().mapToDouble(lp -> parseMoney(lp.getUc())).sum();
        double pctCalled      = totalCapCommit > 0 ? totalCalledCap / totalCapCommit : 0;

        // Uncalled-weighted population shares (SHADOW_BB_ANALYSIS Table 1):
        // each metric = Σ(uncalled of matching LPs) ÷ Σ(total uncalled), not a headcount ratio.
        double instUncalled   = lps.stream().filter(lp -> "Institutional".equals(lp.getInstVsHnw())).mapToDouble(lp -> parseMoney(lp.getUc())).sum();
        double hnwUncalled    = lps.stream().filter(lp -> "HNW".equals(lp.getInstVsHnw())).mapToDouble(lp -> parseMoney(lp.getUc())).sum();
        double igUncalled     = lps.stream().filter(lp -> lp.isIg()).mapToDouble(lp -> parseMoney(lp.getUc())).sum();
        double pctInstitutional = totalUncalled > 0 ? instUncalled / totalUncalled : 0;
        double pctHNW           = totalUncalled > 0 ? hnwUncalled  / totalUncalled : 0;
        double igRatio          = totalUncalled > 0 ? igUncalled   / totalUncalled : 0;

        List<Double> sortedUc = lps.stream()
            .mapToDouble(lp -> parseMoney(lp.getUc()))
            .boxed().sorted(Comparator.reverseOrder()).toList();
        double top10Uc        = sortedUc.stream().limit(10).mapToDouble(d -> d).sum();
        double top20Uc        = sortedUc.stream().limit(20).mapToDouble(d -> d).sum();
        double pctTop10       = totalUncalled > 0 ? top10Uc / totalUncalled : 0;
        double pctTop20       = totalUncalled > 0 ? top20Uc / totalUncalled : 0;
        // % of Uncalled Capital from LPs with AUM > $25bn (parseMoney returns $millions, so $25bn = 25_000).
        double gt25bnUncalled       = lps.stream().filter(lp -> parseMoney(lp.getAum()) > 25_000).mapToDouble(lp -> parseMoney(lp.getUc())).sum();
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
        for (var lp : lps) {
            double rate = calculator.advanceRateFraction(lp);
            String rateKey = String.format("%.0f%%", rate * 100);
            busaMap.computeIfAbsent(rateKey, k -> new double[]{0, 0});
            busaMap.get(rateKey)[0]++;
            busaMap.get(rateKey)[1] += parseMoney(lp.getUc());
        }
        List<Map<String, Object>> busaBreakdown = busaMap.entrySet().stream()
            .filter(e -> e.getValue()[0] > 0)
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
        for (var lp : lps) {
            String rateKey = lp.getAgentRate() != null && !lp.getAgentRate().isBlank()
                ? lp.getAgentRate() : "0%";
            agentMap.computeIfAbsent(rateKey, k -> new double[]{0, 0});
            agentMap.get(rateKey)[0]++;
            agentMap.get(rateKey)[1] += parseMoney(lp.getUc());
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
        for (var lp : lps) {
            String bucket = canonicalClassBucket(lp.getCls());
            clsMap.get(bucket)[0]++;
            clsMap.get(bucket)[1] += parseMoney(lp.getUc());
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

    /** Called Capital for one LP ($millions): the stored value when present, else the calculated
     *  Capital Commitments − Uncalled Capital (never negative). */
    private static double calledCapM(com.ubs.pesubapi.entity.Lp lp) {
        if (lp.getCalledCap() != null && !lp.getCalledCap().isBlank()) return parseMoney(lp.getCalledCap());
        return Math.max(0, parseMoney(lp.getCapCommit()) - parseMoney(lp.getUc()));
    }

    private static double parseMoney(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            String clean = value.replaceAll("[$,]", "");
            double mult = 1;
            if (clean.toUpperCase().endsWith("M")) { mult = 1;     clean = clean.substring(0, clean.length() - 1); }
            else if (clean.toUpperCase().endsWith("B")) { mult = 1000; clean = clean.substring(0, clean.length() - 1); }
            else if (clean.toUpperCase().endsWith("T")) { mult = 1_000_000; clean = clean.substring(0, clean.length() - 1); }
            else if (clean.toUpperCase().endsWith("K")) { mult = 0.001; clean = clean.substring(0, clean.length() - 1); }
            return Double.parseDouble(clean) * mult;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Rolls a granular LP classification (UBS or legacy taxonomy) up into one of the four
     *  canonical eligibility buckets used by SHADOW_BB_ANALYSIS Table 5. */
    private static String canonicalClassBucket(String cls) {
        if (cls == null || cls.isBlank()) return "Excluded Investors";
        return switch (cls) {
            case "Rated Investor", "Rated" -> "Rated Investors";
            case "FoF & Other > $10Bn AUM", "Corp Pension > $5Bn Assets", "Unrated NAV > $1Bn",
                 "Unrated >2bn", "Unrated 1–2bn" -> "Unrated Investors";
            case "Other Institutional", "Eligible", "Included (PWM)" -> "Eligible Investors";
            case "Excluded" -> "Excluded Investors";
            default -> "Eligible Investors";
        };
    }

    private static double parseRatePct(String rate) {
        if (rate == null) return 0;
        try { return Double.parseDouble(rate.replace("%", "").trim()); }
        catch (NumberFormatException e) { return 0; }
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
