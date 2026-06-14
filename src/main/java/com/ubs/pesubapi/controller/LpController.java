package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.IngestRequest;
import com.ubs.pesubapi.dto.IngestResult;
import com.ubs.pesubapi.dto.LpClassificationRequest;
import com.ubs.pesubapi.dto.LpDto;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.LpClassificationService;
import com.ubs.pesubapi.service.LpIngestService;
import com.ubs.pesubapi.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/lps")
public class LpController {

    private final LpRepository           repo;
    private final NotificationService    notifier;
    private final AuditLogService        auditService;
    private final LpIngestService        ingestService;
    private final LpClassificationService classificationService;

    public LpController(LpRepository repo, NotificationService notifier,
                        AuditLogService auditService, LpIngestService ingestService,
                        LpClassificationService classificationService) {
        this.repo                  = repo;
        this.notifier              = notifier;
        this.auditService          = auditService;
        this.ingestService         = ingestService;
        this.classificationService = classificationService;
    }

    @PostMapping("/ingest")
    public IngestResult ingest(@RequestBody IngestRequest request, HttpServletRequest httpRequest) {
        IngestResult result = ingestService.ingest(0, request);
        if (result.updated() > 0) {
            auditService.log("LP Data Updated",
                result.updated() + " LP records updated from " + result.templateFormat() + " extraction",
                request.facilityId(), "J. Smith", auditService.extractIp(httpRequest));
        }
        return result;
    }

    @GetMapping
    public List<LpDto> list(@RequestParam(required = false) Integer facilityId,
                             @RequestParam(required = false) String cls,
                             @RequestParam(required = false) String search) {
        List<Lp> lps;
        if (facilityId != null && cls != null) {
            lps = repo.findByFacilityIdAndClsOrderByInvestorNameAsc(facilityId, cls);
        } else if (facilityId != null && search != null) {
            lps = repo.findByFacilityIdAndInvestorNameContainingIgnoreCaseOrderByInvestorNameAsc(facilityId, search);
        } else if (facilityId != null) {
            lps = repo.findByFacilityIdOrderByInvestorNameAsc(facilityId);
        } else {
            lps = repo.findAllByOrderByInvestorNameAsc();
        }
        return lps.stream().map(LpDto::from).toList();
    }

    /**
     * Batch-applies the credit officer's classification & rate edits onto persisted LP Master
     * records (the "Save" action on the LP Classification & Rate Assignment screen). Rows are
     * matched to existing records by (facilityId, name); unmatched rows are ignored.
     */
    @PatchMapping("/classification")
    public Map<String, Integer> patchClassification(@RequestBody LpClassificationRequest req,
                                                     HttpServletRequest request) {
        int updated = classificationService.applyClassifications(req);
        if (updated > 0 && req.facilityId() != null) {
            auditService.log("LP Classification Saved",
                updated + " LP record" + (updated != 1 ? "s" : "")
                    + " updated from Shadow BB classification",
                req.facilityId(), "J. Smith", auditService.extractIp(request));
        }
        return Map.of("updated", updated);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LpDto> get(@PathVariable int id) {
        return repo.findById(id)
            .map(LpDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<LpDto> patch(@PathVariable int id,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        return repo.findById(id).map(lp -> {
            String prevCls = lp.getCls();
            if (body.containsKey("cls"))    lp.setCls((String) body.get("cls"));
            if (body.containsKey("clsTag")) lp.setClsTag((String) body.get("clsTag"));
            if (body.containsKey("abb"))    lp.setAbb((String) body.get("abb"));
            if (body.containsKey("inc"))    lp.setInc((Boolean) body.get("inc"));
            if (body.containsKey("rcl"))    lp.setRcl((Boolean) body.get("rcl"));
            if (body.containsKey("notes"))  lp.setNotes((String) body.get("notes"));
            lp.setUpdatedAt(LocalDateTime.now());
            Lp saved = repo.save(lp);
            if (body.containsKey("cls") && !Objects.equals(lp.getCls(), prevCls)) {
                notifier.broadcast(lp.getInvestorName() + " reclassified to " + lp.getCls());
                String detail = lp.getInvestorName() + " → " + lp.getCls()
                    + (prevCls != null ? " (was " + prevCls + ")" : "");
                auditService.log("LP Reclassified", detail, lp.getFacilityId(),
                    "J. Smith", auditService.extractIp(request));
            }
            return ResponseEntity.ok(LpDto.from(saved));
        }).orElse(ResponseEntity.notFound().build());
    }
}
