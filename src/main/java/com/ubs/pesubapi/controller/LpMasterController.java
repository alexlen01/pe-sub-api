package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.IngestSummary;
import com.ubs.pesubapi.dto.LpMasterDto;
import com.ubs.pesubapi.dto.LpMasterIngestRow;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.LpMasterIngestService;
import com.ubs.pesubapi.service.LpMasterService;
import com.ubs.pesubapi.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/lp-master")
public class LpMasterController {

    private static final Logger log = LoggerFactory.getLogger(LpMasterController.class);

    private final LpMasterRepository repo;
    private final LpMasterIngestService ingestService;
    private final LpMasterService lpMasterService;
    private final AuditLogService auditService;
    private final NotificationService notifier;
    private final CurrentUserService currentUser;

    public LpMasterController(LpMasterRepository repo, LpMasterIngestService ingestService,
                              LpMasterService lpMasterService, AuditLogService auditService,
                              NotificationService notifier, CurrentUserService currentUser) {
        this.repo = repo;
        this.ingestService = ingestService;
        this.lpMasterService = lpMasterService;
        this.auditService = auditService;
        this.notifier = notifier;
        this.currentUser = currentUser;
    }

    /**
     * Bulk upsert from the pe-sub-jobs LP Master feed, keyed by investor name.
     * Replaces the batch job's direct {@code INSERT ... ON CONFLICT (investor_name)} against
     * the lp_master table — pe-sub-api owns the schema, so all writes route through here.
     */
    @PostMapping("/ingest")
    public IngestSummary ingest(@RequestBody List<LpMasterIngestRow> rows) {
        IngestSummary result = ingestService.ingest(rows);
        log.info("LP Master ingest completed rows={} created={} updated={} skipped={}",
            rows.size(), result.created(), result.updated(), result.skipped());
        return result;
    }

    @GetMapping
    public List<LpMasterDto> list() {
        List<LpMasterDto> result = repo.findAllByOrderByUbsClassificationAscInvestorNameAsc()
                .stream()
                .map(LpMasterDto::from)
                .toList();
        log.info("LP Master listed count={}", result.size());
        return result;
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("count", repo.count());
    }

    @GetMapping("/investor-types")
    public List<String> investorTypes() {
        List<String> result = repo.findDistinctInvestorTypes().stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim())
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        log.info("LP Master investor types listed count={}", result.size());
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<LpMasterDto> get(@PathVariable int id) {
        return repo.findById(id)
                .map(LpMasterDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Hard-delete an LP Master row — the manual correction path for LPs erroneously ingested
     * past analyst/reviewer checks. Facility LP records referencing the row are detached, not
     * deleted. 404 if the row does not exist. ANALYST-gated (curation surface).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id, HttpServletRequest request) {
        LpMasterService.LpMasterDeletion result = lpMasterService.delete(id);
        auditService.log("LP Master Deleted",
            "'" + result.investorName() + "' removed from LP Master ("
                + result.detachedRecords() + " facility LP record(s) detached)",
            null, currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        notifier.broadcast(result.investorName() + " deleted from LP Master");
        return ResponseEntity.noContent().build();
    }
}
