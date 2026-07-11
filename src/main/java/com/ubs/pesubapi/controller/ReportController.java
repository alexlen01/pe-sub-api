package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.*;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.ReportService;
import com.ubs.pesubapi.service.CollateralPdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService      reports;
    private final CurrentUserService currentUser;
    private final CollateralPdfService collateralPdf;

    public ReportController(ReportService reports, CurrentUserService currentUser, CollateralPdfService collateralPdf) {
        this.reports     = reports;
        this.currentUser = currentUser;
        this.collateralPdf = collateralPdf;
    }

    @GetMapping(value = "/collateral/{facilityId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> collateralPdf(@PathVariable int facilityId,
                                                 @RequestParam(required = false) Integer snapshotId,
                                                 @RequestParam(required = false, defaultValue = "DRAFT - For Internal Review") String watermark,
                                                 @RequestParam(defaultValue = "LPRecord") String detail,
                                                 @RequestParam(defaultValue = "included") String includeLps,
                                                 @RequestParam(defaultValue = "true") boolean catSummary,
                                                 @RequestParam(defaultValue = "true") boolean coverageTrend,
                                                 @RequestParam(defaultValue = "true") boolean concAnalysis,
                                                 @RequestParam(defaultValue = "true") boolean reclass,
                                                 @RequestParam(defaultValue = "true") boolean quality) {
        CollateralReportDto report = reports.collateral(facilityId, snapshotId);
        String filename = "bb-certificate-" + report.facilityName().replaceAll("[^A-Za-z0-9]+", "-").toLowerCase() + ".pdf";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(collateralPdf.create(report, watermark,
                new CollateralPdfService.Options(detail, includeLps, catSummary, coverageTrend, concAnalysis, reclass, quality)));
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
