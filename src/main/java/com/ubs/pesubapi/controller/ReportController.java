package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.*;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService      reports;
    private final CurrentUserService currentUser;

    public ReportController(ReportService reports, CurrentUserService currentUser) {
        this.reports     = reports;
        this.currentUser = currentUser;
    }

    /** Collateral Market Value & Coverage — certificate data from a BB snapshot
     *  (the latest one unless an explicit snapshotId is given). */
    @GetMapping("/collateral/{facilityId}")
    public CollateralReportDto collateral(@PathVariable int facilityId,
                                          @RequestParam(required = false) Integer snapshotId) {
        return reports.collateral(facilityId, snapshotId);
    }

    /** Effective Advance Rate trend — one point per snapshot, oldest first. */
    @GetMapping("/ear/{facilityId}")
    public List<EarPointDto> ear(@PathVariable int facilityId) {
        return reports.earTrend(facilityId);
    }

    /** UBS exposure aggregated by agent bank from each facility's latest snapshot. */
    @GetMapping("/agent-banks")
    public List<AgentBankExposureDto> agentBanks() {
        return reports.agentBankExposure();
    }

    @GetMapping("/concentration/{facilityId}")
    public Map<String, List<BbBreach>> concentration(@PathVariable int facilityId) {
        return Map.of("breaches", reports.concentrationBreaches(facilityId));
    }

    // ── Report history ────────────────────────────────────────────────────────────

    @GetMapping("/history")
    public List<ReportHistoryDto> history() {
        return reports.history();
    }

    @PostMapping("/history")
    public ResponseEntity<ReportHistoryDto> recordHistory(@RequestBody CreateReportHistoryRequest req) {
        ReportHistoryDto saved = reports.recordHistory(req, currentUser.displayName());
        return ResponseEntity.status(201).body(saved);
    }
}
