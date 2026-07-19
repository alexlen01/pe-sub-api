package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.IngestRequest;
import com.ubs.pesubapi.dto.IngestResult;
import com.ubs.pesubapi.dto.IngestSummary;
import com.ubs.pesubapi.dto.LpClassificationRequest;
import com.ubs.pesubapi.dto.LpRecordDto;
import com.ubs.pesubapi.dto.LpRecordSeedRow;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.LpClassificationService;
import com.ubs.pesubapi.service.LpIngestService;
import com.ubs.pesubapi.service.LpRecordSeedService;
import com.ubs.pesubapi.service.NotificationService;
import com.ubs.pesubapi.service.ShadowBbService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/lpRecords")
public class LpRecordController {

    private static final Logger log = LoggerFactory.getLogger(LpRecordController.class);

    private final LpRecordRepository           repo;
    private final NotificationService    notifier;
    private final AuditLogService        auditService;
    private final LpIngestService        ingestService;
    private final LpClassificationService classificationService;
    private final LpRecordSeedService    seedService;
    private final CurrentUserService     currentUser;
    private final ShadowBbService        shadowBbService;

    public LpRecordController(LpRecordRepository repo, NotificationService notifier,
                        AuditLogService auditService, LpIngestService ingestService,
                        LpClassificationService classificationService,
                        LpRecordSeedService seedService,
                        CurrentUserService currentUser,
                        ShadowBbService shadowBbService) {
        this.repo                  = repo;
        this.notifier              = notifier;
        this.auditService          = auditService;
        this.ingestService         = ingestService;
        this.classificationService = classificationService;
        this.seedService           = seedService;
        this.currentUser           = currentUser;
        this.shadowBbService       = shadowBbService;
    }

    /**
     * Seeds facility LP records from the pe-sub-jobs feed (facility + LP Master resolved by
     * name server-side, LP Master profile merged). Existing (facility, investor) pairs are
     * skipped, never overwritten — lp_records intentionally has no unique constraint on that
     * pair (multi-sleeve), so idempotency lives here rather than in an ON CONFLICT clause.
     */
    @PostMapping("/seed")
    public IngestSummary seed(@RequestBody List<LpRecordSeedRow> rows) {
        IngestSummary result = seedService.seed(rows);
        log.info("LP record seed completed rows={} created={} skipped={}",
            rows.size(), result.created(), result.skipped());
        return result;
    }

    @PostMapping("/ingest")
    public IngestResult ingest(@RequestBody IngestRequest request, HttpServletRequest httpRequest) {
        IngestResult result = ingestService.ingest(0, request);
        log.info("LpRecord ingest completed facilityId={} template='{}' updated={} queued={} skipped={}",
            request.facilityId(), result.templateFormat(), result.updated(), result.queued(), result.skipped());
        if (result.updated() > 0) {
            auditService.log("LP Data Updated",
                result.updated() + " LP records updated from " + result.templateFormat() + " extraction",
                request.facilityId(), currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(httpRequest));
        }
        return result;
    }

    @GetMapping
    public List<LpRecordDto> list(@RequestParam(required = false) Integer facilityId,
                             @RequestParam(required = false) String cls,
                             @RequestParam(required = false) String search) {
        List<LpRecord> lps;
        if (facilityId != null && cls != null) {
            lps = repo.findByFacilityIdAndClsOrderByClsAscInvestorNameAsc(facilityId, cls);
        } else if (facilityId != null && search != null) {
            lps = repo.findByFacilityIdAndInvestorNameContainingIgnoreCaseOrderByClsAscInvestorNameAsc(facilityId, search);
        } else if (facilityId != null) {
            lps = repo.findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(facilityId);
        } else {
            lps = repo.findAllByOrderByClsAscInvestorNameAsc();
        }
        return lps.stream().map(LpRecordDto::from).toList();
    }

    /**
     * Applies the credit officer's classification & rate edits onto persisted LP Master records
     * (the LP Category & Rate Assignment screen). Rows are matched to existing records by
     * (facilityId, name); unmatched rows are ignored.
     *
     * <p>The screen auto-saves each edited row as the user types — those calls are silent. Only the
     * aggregated flush sent when the user leaves the screen carries {@code audit: true}, which
     * writes a single audit entry recording the number of LP records touched in the session.
     */
    @PatchMapping("/classification")
    public Map<String, Integer> patchClassification(@RequestBody LpClassificationRequest req,
                                                     HttpServletRequest request) {
        int updated = classificationService.applyClassifications(req);
        log.info("LP Classification batch applied facilityId={} rows={} updated={} audit={}",
            req.facilityId(), req.rows() != null ? req.rows().size() : 0, updated, req.audit());
        if (updated > 0 && req.facilityId() != null && Boolean.TRUE.equals(req.audit())) {
            auditService.log("LP Category Saved",
                updated + " LP record" + (updated != 1 ? "s" : "")
                    + " updated from Shadow BB classification",
                req.facilityId(), currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        }
        return Map.of("updated", updated);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LpRecordDto> get(@PathVariable int id) {
        return repo.findById(id)
            .map(LpRecordDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<LpRecordDto> patch(@PathVariable int id,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        return repo.findById(id).map(lpRecord -> {
            String prevCls = lpRecord.getCls();
            String prevAgentCls = lpRecord.getAgentCls();
            if (body.containsKey("investor_type")) lpRecord.setInvestorType((String) body.get("investor_type"));
            if (body.containsKey("investorType"))  lpRecord.setInvestorType((String) body.get("investorType"));
            if (body.containsKey("fund_sleeve"))   lpRecord.setFundSleeve((String) body.get("fund_sleeve"));
            if (body.containsKey("fundSleeve"))    lpRecord.setFundSleeve((String) body.get("fundSleeve"));
            if (body.containsKey("inst_vs_hnw"))   lpRecord.setInstVsHnw((String) body.get("inst_vs_hnw"));
            if (body.containsKey("instVsHnw"))     lpRecord.setInstVsHnw((String) body.get("instVsHnw"));
            if (body.containsKey("region_location")) lpRecord.setRegionLocation((String) body.get("region_location"));
            if (body.containsKey("regionLocation"))  lpRecord.setRegionLocation((String) body.get("regionLocation"));
            if (body.containsKey("region"))          lpRecord.setRegionLocation((String) body.get("region"));
            if (body.containsKey("agentCls")) {
                lpRecord.setAgentCls((String) body.get("agentCls"));
                lpRecord.setAgentClsSource("USER_EDITED");
            }
            if (body.containsKey("cls"))    lpRecord.setCls((String) body.get("cls"));
            if (body.containsKey("clsTag")) lpRecord.setClsTag((String) body.get("clsTag"));
            if (body.containsKey("abb"))    lpRecord.setAbb((String) body.get("abb"));
            if (body.containsKey("inc"))    lpRecord.setInc((Boolean) body.get("inc"));
            if (body.containsKey("rcl"))    lpRecord.setRcl((Boolean) body.get("rcl"));
            if (body.containsKey("notes"))  lpRecord.setNotes((String) body.get("notes"));
            boolean classificationChanged = !Objects.equals(lpRecord.getCls(), prevCls)
                || !Objects.equals(lpRecord.getAgentCls(), prevAgentCls);
            if (classificationChanged) lpRecord.setRcl(true);
            lpRecord.setUpdatedAt(LocalDateTime.now());
            LpRecord saved = repo.save(lpRecord);
            log.info("LpRecord patched id={} facilityId={} investor='{}' fields={}",
                id, saved.getFacilityId(), saved.getInvestorName(), body.keySet());
            if (classificationChanged) {
                notifier.broadcast(lpRecord.getInvestorName() + " was reclassified");
                String detail = lpRecord.getInvestorName()
                    + ": Agent " + prevAgentCls + " → " + lpRecord.getAgentCls()
                    + "; UBS " + prevCls + " → " + lpRecord.getCls();
                auditService.log("LpRecord Reclassified", detail, lpRecord.getFacilityId(),
                    currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
            }
            return ResponseEntity.ok(LpRecordDto.from(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Hard-delete a facility LP record — the manual correction path for a row that slipped
     * through extraction review and reviewer checks. Ranks for the facility's remaining LPs are
     * recomputed in the same transaction; the current BB snapshot refreshes on the next
     * Run / Re-run Shadow BB. 404 if the record does not exist.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id, HttpServletRequest request) {
        ShadowBbService.LpRecordDeletion result = shadowBbService.deleteLpRecord(id);
        auditService.log("LP Record Deleted",
            "'" + result.investorName() + "' deleted from facility LP records; ranks recomputed",
            result.facilityId(), currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        notifier.broadcast(result.investorName() + " deleted from LP records");
        log.info("LP record deleted id={} facilityId={} investor='{}'",
            id, result.facilityId(), result.investorName());
        return ResponseEntity.noContent().build();
    }
}
