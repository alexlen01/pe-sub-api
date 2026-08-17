package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.IngestSummary;
import com.ubs.pesubapi.dto.LpMasterDto;
import com.ubs.pesubapi.dto.LpMasterIngestRow;
import com.ubs.pesubapi.dto.LpMasterUpdateRequest;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.LpAliasService;
import com.ubs.pesubapi.service.LpMasterIngestService;
import com.ubs.pesubapi.service.LpMasterService;
import com.ubs.pesubapi.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    private final LpAliasService aliasService;
    private final AuditLogService auditService;
    private final NotificationService notifier;
    private final CurrentUserService currentUser;

    public LpMasterController(LpMasterRepository repo, LpMasterIngestService ingestService,
                              LpMasterService lpMasterService, LpAliasService aliasService,
                              AuditLogService auditService,
                              NotificationService notifier, CurrentUserService currentUser) {
        this.repo = repo;
        this.ingestService = ingestService;
        this.lpMasterService = lpMasterService;
        this.aliasService = aliasService;
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

    /**
     * Clears the entire LP Master table ahead of a full repopulate from the one-off LP DB
     * extract feed ("override, do not preserve"). SERVICE-gated — invoked by the pe-sub-jobs
     * lp-master ingest job as a pre-step, never a human surface. Facility LP records are
     * detached, not deleted. Idempotent: a no-op on an already-empty table.
     */
    @PostMapping("/clear")
    public Map<String, Long> clear(HttpServletRequest request) {
        LpMasterService.LpMasterClear result = lpMasterService.clearAll();
        auditService.log("LP Master Cleared",
            result.deletedMasters() + " LP Master row(s) removed ahead of repopulate ("
                + result.detachedRecords() + " facility LP record(s) detached)",
            null, currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        notifier.broadcast(result.deletedMasters() + " LP Master rows cleared for repopulate");
        log.info("LP Master clear completed deletedMasters={} detachedRecords={}",
            result.deletedMasters(), result.detachedRecords());
        return Map.of("deletedMasters", result.deletedMasters(),
                      "detachedRecords", (long) result.detachedRecords());
    }

    /**
     * The LP Master Records list. Each row carries its resolved hierarchy — the ultimate parent it
     * routes to and its direct-child count — so the screen can show what an Accept would apply
     * without a lookup per row.
     */
    @GetMapping
    public List<LpMasterDto> list() {
        List<LpMasterDto> result = lpMasterService.listWithHierarchy();
        log.info("LP Master listed count={}", result.size());
        return result;
    }

    /** Direct children of one LP Master record — the feeders/SPVs routing their profile to it. */
    @GetMapping("/{id}/children")
    public ResponseEntity<List<LpMasterDto>> children(@PathVariable int id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(repo.findByParentIdOrderByInvestorNameAsc(id).stream()
                .map(LpMasterDto::from)
                .toList());
    }

    /** Agent BB strings previously accepted against this record (the Phase 5 alias feedback loop). */
    @GetMapping("/{id}/aliases")
    public ResponseEntity<List<String>> aliases(@PathVariable int id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(aliasService.aliasesFor(id));
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
        return lpMasterService.getWithHierarchy(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Replace the editable subset of an LP Master record — the LP Master Records panel's Save.
     * PUT rather than PATCH because the panel submits every field it renders, so an omitted value
     * means "cleared" and there is no sparse-merge ambiguity. Parent linkage is resolved here:
     * see {@link LpMasterService#update}. ANALYST-gated (curation surface).
     *
     * @return 200 with the saved record and its re-resolved hierarchy, 404 when {@code id} is unknown
     */
    @PutMapping("/{id}")
    public ResponseEntity<LpMasterDto> update(@PathVariable int id,
                                              @Valid @RequestBody LpMasterUpdateRequest request,
                                              HttpServletRequest httpRequest) {
        LpMasterDto saved = lpMasterService.update(id, request);
        auditService.log("LP Master Updated",
            "'" + saved.investorName() + "' updated"
                + (saved.ultimateParent() != null ? " — routes to " + saved.ultimateParent() : ""),
            null, currentUser.uuName(), currentUser.auditDisplayName(),
            auditService.extractIp(httpRequest));
        notifier.broadcast(saved.investorName() + " updated in LP Master");
        return ResponseEntity.ok(saved);
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
