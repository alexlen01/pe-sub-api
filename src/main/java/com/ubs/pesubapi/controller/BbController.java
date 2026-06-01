package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.BbCalculationService;
import com.ubs.pesubapi.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bb")
public class BbController {

    private final FacilityRepository    facilityRepo;
    private final LpRepository          lpRepo;
    private final BbSnapshotRepository  snapshotRepo;
    private final BbCalculationService  calculator;
    private final NotificationService   notifier;
    private final AuditLogService       auditService;

    public BbController(FacilityRepository facilityRepo, LpRepository lpRepo,
                        BbSnapshotRepository snapshotRepo, BbCalculationService calculator,
                        NotificationService notifier, AuditLogService auditService) {
        this.facilityRepo = facilityRepo;
        this.lpRepo       = lpRepo;
        this.snapshotRepo = snapshotRepo;
        this.calculator   = calculator;
        this.notifier     = notifier;
        this.auditService = auditService;
    }

    @PostMapping("/run/{facilityId}")
    public ResponseEntity<BbSnapshot> run(@PathVariable int facilityId, HttpServletRequest request) {
        Facility facility = facilityRepo.findById(facilityId)
            .orElseThrow(() -> new ResourceNotFoundException("Facility not found: " + facilityId));

        List<com.ubs.pesubapi.entity.Lp> lps = lpRepo.findByFacilityIdOrderByRankAsc(facilityId);
        BbResult result = calculator.compute(lps, facility.getConcLimitM().doubleValue());

        BbSnapshot snapshot = new BbSnapshot();
        snapshot.setFacilityId(facilityId);
        snapshot.setResult(result);
        BbSnapshot saved = snapshotRepo.save(snapshot);

        facility.setLastRunAt(LocalDateTime.now());
        facilityRepo.save(facility);

        String detail = lps.size() + " LPs · UBS BB $"
            + String.format("%.1f", result.summary().totalUBB()) + "M";
        notifier.broadcast("Shadow BB calculated for " + facility.getName()
            + " — UBS BB $" + String.format("%.1f", result.summary().totalUBB()) + "M");
        auditService.log("BB Recalculated", detail, facilityId, "J. Smith", auditService.extractIp(request));

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

    @GetMapping("/summary-ext/{facilityId}")
    public ResponseEntity<Map<String, Object>> summaryExt(@PathVariable int facilityId) {
        List<com.ubs.pesubapi.entity.Lp> lps = lpRepo.findByFacilityIdOrderByRankAsc(facilityId);
        if (lps.isEmpty()) return ResponseEntity.ok(emptySummaryExt());

        int    totalLPs       = lps.size();
        double totalCapCommit = lps.stream().mapToDouble(lp -> parseMoney(lp.getCapCommit())).sum();
        double totalCalledCap = lps.stream().mapToDouble(lp -> parseMoney(lp.getCalledCap())).sum();
        double totalUncalled  = lps.stream().mapToDouble(lp -> parseMoney(lp.getUc())).sum();
        double pctCalled      = totalCapCommit > 0 ? totalCalledCap / totalCapCommit * 100 : 0;
        long   igCount        = lps.stream().filter(com.ubs.pesubapi.entity.Lp::isIg).count();
        double igRatio        = totalLPs > 0 ? (double) igCount / totalLPs * 100 : 0;

        List<Double> sortedUc = lps.stream()
            .mapToDouble(lp -> parseMoney(lp.getUc()))
            .boxed().sorted(Comparator.reverseOrder()).toList();
        double top10Uc = sortedUc.stream().limit(10).mapToDouble(d -> d).sum();
        double top20Uc = sortedUc.stream().limit(20).mapToDouble(d -> d).sum();
        double pctTop10       = totalUncalled > 0 ? top10Uc / totalUncalled * 100 : 0;
        double pctTop20       = totalUncalled > 0 ? top20Uc / totalUncalled * 100 : 0;
        long   gt2MCount      = lps.stream().filter(lp -> parseMoney(lp.getUc()) > 2).count();
        double pctUncalledGt2M = totalLPs > 0 ? (double) gt2MCount / totalLPs * 100 : 0;

        double agentBBRaw = 0, ubsBBRaw = 0;
        Optional<BbSnapshot> latest = snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId);
        if (latest.isPresent() && latest.get().getResult() != null) {
            agentBBRaw = latest.get().getResult().summary().totalABB();
            ubsBBRaw   = latest.get().getResult().summary().totalUBB();
        }
        double ubsAdvRate = totalUncalled > 0 ? ubsBBRaw / totalUncalled * 100 : 0;

        Map<String, List<com.ubs.pesubapi.entity.Lp>> byClass =
            lps.stream().collect(Collectors.groupingBy(lp -> lp.getCls() != null ? lp.getCls() : "Unclassified"));
        List<Map<String, Object>> clsBreakdown = byClass.entrySet().stream().map(e -> {
            double dollars = e.getValue().stream().mapToDouble(lp -> parseMoney(lp.getUc())).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label",   e.getKey());
            row.put("count",   e.getValue().size());
            row.put("dollars", dollars);
            row.put("pct",     totalUncalled > 0 ? dollars / totalUncalled * 100 : 0);
            return row;
        }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCapCommit",     totalCapCommit);
        out.put("totalCalledCap",     totalCalledCap);
        out.put("pctCalled",          pctCalled);
        out.put("totalAllUncalled",   totalUncalled);
        out.put("totalLPs",           totalLPs);
        out.put("pctInstitutional",   igRatio);
        out.put("pctHNW",             0.0);
        out.put("pctTop10",           pctTop10);
        out.put("pctTop20",           pctTop20);
        out.put("igRatio",            igRatio);
        out.put("pctUncalledGt2M",    pctUncalledGt2M);
        out.put("facilitySize",       0.0);
        out.put("ubsParticipation",   0.0);
        out.put("ubsParticipationPct",0.0);
        out.put("facilityLTV",        0.0);
        out.put("availableCommit",    0.0);
        out.put("facilityAdvRate",    0.0);
        out.put("agentBBRaw",         agentBBRaw);
        out.put("ubsBBRaw",           ubsBBRaw);
        out.put("ubsAdvRate",         ubsAdvRate);
        out.put("busaBreakdown",      List.of());
        out.put("agentBreakdown",     List.of());
        out.put("clsBreakdown",       clsBreakdown);
        return ResponseEntity.ok(out);
    }

    private static double parseMoney(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.replaceAll("[,$%]", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private Map<String, Object> emptySummaryExt() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("totalCapCommit","totalCalledCap","pctCalled","totalAllUncalled",
                "totalLPs","pctInstitutional","pctHNW","pctTop10","pctTop20","igRatio",
                "pctUncalledGt2M","facilitySize","ubsParticipation","ubsParticipationPct",
                "facilityLTV","availableCommit","facilityAdvRate","agentBBRaw","ubsBBRaw","ubsAdvRate")) {
            out.put(k, 0.0);
        }
        out.put("busaBreakdown",  List.of());
        out.put("agentBreakdown", List.of());
        out.put("clsBreakdown",   List.of());
        return out;
    }
}
