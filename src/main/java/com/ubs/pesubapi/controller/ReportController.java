package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.*;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.ReportService;
import com.ubs.pesubapi.service.CollateralPdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

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
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable _ = MDC.putCloseable("reportRequestId", requestId)) {
            log.info("collateral_pdf_request facilityId={} snapshotId={} detail={} includeLps={} catSummary={} coverageTrend={} concAnalysis={} reclass={} quality={} watermark={}",
                facilityId, snapshotId, detail, includeLps, catSummary, coverageTrend, concAnalysis, reclass, quality,
                watermark == null ? "null" : (watermark.isBlank() ? "blank" : "present"));
            CollateralReportDto report = reports.collateral(facilityId, snapshotId);
            String filename = "bb-certificate-" + report.facilityName().replaceAll("[^A-Za-z0-9]+", "-").toLowerCase() + ".pdf";
            byte[] pdf = collateralPdf.create(report, watermark,
                new CollateralPdfService.Options(detail, includeLps, catSummary, coverageTrend, concAnalysis, reclass, quality));
            log.info("collateral_pdf_success facilityId={} snapshotId={} resolvedSnapshotId={} filename={} bytes={}",
                facilityId, snapshotId, report.snapshotId(), filename, pdf.length);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
        }
    }

    /** Collateral Market Value & Coverage — certificate data from a BB snapshot
     *  (the latest one unless an explicit snapshotId is given). */
    @GetMapping("/collateral/{facilityId}")
    public CollateralReportDto collateral(@PathVariable int facilityId,
                                          @RequestParam(required = false) Integer snapshotId) {
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable _ = MDC.putCloseable("reportRequestId", requestId)) {
            log.info("collateral_json_request facilityId={} snapshotId={}", facilityId, snapshotId);
            return reports.collateral(facilityId, snapshotId);
        }
    }

    /** Effective Advance Rate trend — one point per snapshot, oldest first. */
    @GetMapping("/ear/{facilityId}")
    public List<EarPointDto> ear(@PathVariable int facilityId) {
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable _ = MDC.putCloseable("reportRequestId", requestId)) {
            log.info("ear_request facilityId={}", facilityId);
            return reports.earTrend(facilityId);
        }
    }

    /** UBS exposure aggregated by agent bank from each facility's latest snapshot. */
    @GetMapping("/agent-banks")
    public List<AgentBankExposureDto> agentBanks() {
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable _ = MDC.putCloseable("reportRequestId", requestId)) {
            log.info("agent_bank_exposure_request");
            return reports.agentBankExposure();
        }
    }

    @GetMapping("/concentration/{facilityId}")
    public Map<String, List<BbBreach>> concentration(@PathVariable int facilityId) {
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable _ = MDC.putCloseable("reportRequestId", requestId)) {
            log.info("concentration_request facilityId={}", facilityId);
            return Map.of("breaches", reports.concentrationBreaches(facilityId));
        }
    }

    // ── Report history ────────────────────────────────────────────────────────────

    @GetMapping("/history")
    public List<ReportHistoryDto> history() {
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable _ = MDC.putCloseable("reportRequestId", requestId)) {
            log.info("report_history_list_request");
            return reports.history();
        }
    }

    @PostMapping("/history")
    public ResponseEntity<ReportHistoryDto> recordHistory(@RequestBody CreateReportHistoryRequest req) {
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable _ = MDC.putCloseable("reportRequestId", requestId)) {
            log.info("report_history_create_request report={} facilityId={} format={}",
                req == null ? null : req.report(),
                req == null ? null : req.facilityId(),
                req == null ? null : req.format());
            ReportHistoryDto saved = reports.recordHistory(req, currentUser.uuName());
            return ResponseEntity.status(201).body(saved);
        }
    }
}
