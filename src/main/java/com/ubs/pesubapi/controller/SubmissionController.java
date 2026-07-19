package com.ubs.pesubapi.controller;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ubs.pesubapi.dto.ExtractionResponse;
import com.ubs.pesubapi.dto.IngestRequest;
import com.ubs.pesubapi.dto.ResolvedTemplate;
import com.ubs.pesubapi.dto.WorkbookSignals;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.entity.SubmissionExtraction;
import com.ubs.pesubapi.entity.FmAlias;
import com.ubs.pesubapi.entity.FmCanonicalField;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.FmAliasRepository;
import com.ubs.pesubapi.repository.FmCanonicalFieldRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import com.ubs.pesubapi.security.CurrentUserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ubs.pesubapi.service.AsyncTaskRunner;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.ExtractionClientService;
import com.ubs.pesubapi.service.LpIngestService;
import com.ubs.pesubapi.service.LpMasterWriteBackService;
import com.ubs.pesubapi.service.MatchingService;
import com.ubs.pesubapi.service.NotificationService;
import com.ubs.pesubapi.service.TemplateRecognitionService;
import com.ubs.pesubapi.util.InvestorTypeDeriver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private static final Logger log = LoggerFactory.getLogger(SubmissionController.class);

    /** Submission awaiting independent (Manager) review after the maker submitted it. */
    private static final String STATUS_PENDING_REVIEW = "Pending Review";

    record SubmissionDto(
        Integer id, Integer facilityId, String facilityName,
        String agentBank, String periodMonth, String status,
        String fileName, Integer uploadedBy, String notes,
        int wizardStep, Long version, tools.jackson.databind.JsonNode shadowBbOverrides,
        String ownerUuName, String ownerName,
        String submittedBy, String reviewedBy, String reviewNote,
        LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}


    private final SubmissionRepository           submissions;
    private final FacilityRepository             facilities;
    private final AuditLogService                auditService;
    private final ExtractionClientService        extractionClient;
    private final LpIngestService                ingestService;
    private final LpMasterWriteBackService       lpMasterWriteBack;
    private final MatchingService                matchingService;
    private final SubmissionExtractionRepository extractionRepo;
    private final MatchQueueEntryRepository      matchQueueRepo;
    private final FmCanonicalFieldRepository     canonicalFieldRepo;
    private final FmAliasRepository              aliasRepo;
    private final TemplateRecognitionService     recognitionService;
    private final AsyncTaskRunner                asyncTaskRunner;
    private final ObjectMapper                   mapper;
    private final CurrentUserService             currentUser;
    private final NotificationService            notifier;

    @Value("${app.uploads.path:uploads}")
    private String uploadsPath;

    public SubmissionController(SubmissionRepository submissions,
                                FacilityRepository facilities,
                                AuditLogService auditService,
                                ExtractionClientService extractionClient,
                                LpIngestService ingestService,
                                LpMasterWriteBackService lpMasterWriteBack,
                                MatchingService matchingService,
                                SubmissionExtractionRepository extractionRepo,
                                MatchQueueEntryRepository matchQueueRepo,
                                FmCanonicalFieldRepository canonicalFieldRepo,
                                FmAliasRepository aliasRepo,
                                BbTemplateRepository templateRepo,
                                TemplateRecognitionService recognitionService,
                                AsyncTaskRunner asyncTaskRunner,
                                ObjectMapper mapper,
                                CurrentUserService currentUser,
                                NotificationService notifier) {
        this.submissions        = submissions;
        this.facilities         = facilities;
        this.auditService       = auditService;
        this.extractionClient   = extractionClient;
        this.ingestService      = ingestService;
        this.lpMasterWriteBack  = lpMasterWriteBack;
        this.matchingService    = matchingService;
        this.extractionRepo     = extractionRepo;
        this.matchQueueRepo     = matchQueueRepo;
        this.canonicalFieldRepo = canonicalFieldRepo;
        this.aliasRepo          = aliasRepo;
        this.recognitionService = recognitionService;
        this.asyncTaskRunner    = asyncTaskRunner;
        this.mapper             = mapper;
        this.currentUser        = currentUser;
        this.notifier           = notifier;
    }

    // ── POST /api/submissions ────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(
            @RequestParam("facilityId")           int           facilityId,
            @RequestParam("agentBank")            String        agentBank,
            @RequestParam("periodMonth")          String        periodMonth,
            @RequestParam("file")                 MultipartFile file,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam(value = "forceTemplate", required = false) String forceTemplate,
            HttpServletRequest request
    ) throws IOException {
        Optional<Facility> facilityOpt = facilities.findById(facilityId);
        if (facilityOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Facility " + facilityId + " not found.");
        }

        String original   = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.xlsx";
        // The client controls the original filename: keep only its last path segment and
        // neutralise path-significant characters so the stored name cannot escape uploadsPath.
        String safeName = original
            .replaceAll(".*[\\\\/]", "")
            .replaceAll("[^A-Za-z0-9._ ()\\-]", "_");
        if (safeName.isBlank() || safeName.chars().allMatch(c -> c == '.')) safeName = "upload.xlsx";
        String storedName = UUID.randomUUID() + "_" + safeName;
        Path   dir        = Paths.get(uploadsPath).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path storedPath = dir.resolve(storedName).normalize();
        if (!storedPath.startsWith(dir)) {
            return ResponseEntity.badRequest().body("Invalid file name.");
        }
        Files.copy(file.getInputStream(), storedPath, StandardCopyOption.REPLACE_EXISTING);

        Submission sub = new Submission();
        sub.setFacilityId(facilityId);
        sub.setAgentBank(agentBank);
        sub.setPeriodMonth(periodMonth);
        sub.setFileName(original);
        sub.setFilePath(storedPath.toString());
        if (notes != null && !notes.isBlank()) sub.setNotes(notes.trim());
        // Ownership is established at upload from the authenticated identity (RBAC_ROLES.md). The
        // uuName is the ownership key; the display name is captured so "Submitted By" can render
        // without a user directory.
        sub.setOwnerUuName(currentUser.uuName());
        sub.setOwnerName(currentUser.displayName());
        Submission saved = submissions.save(sub);
        log.info("Submission uploaded id={} facilityId={} agentBank='{}' periodMonth='{}' file='{}' storedPath='{}' forcedTemplate='{}'",
            saved.getId(), facilityId, agentBank, periodMonth, original, storedPath, forceTemplate);

        // Resolve identity + IP on the request thread: the SecurityContext is thread-local and
        // is not visible from the async extraction worker, so both are captured here and passed in.
        String actor = currentUser.uuName();
        String actorDisplay = currentUser.auditDisplayName();
        String ip = auditService.extractIp(request);

        String detail = periodMonth + " · " + original + " · " + agentBank;
        auditService.log("Upload", detail, facilityId, actor, actorDisplay, ip);

        // Heavy Excel parsing must not block the UI thread: persist as Processing, hand the
        // pipeline to the bounded extraction executor, and return immediately (202 Accepted).
        // The client polls the submission until its status flips to Review (or Error).
        asyncTaskRunner.run(() -> {
            try {
                runExtractionPipeline(saved, facilityId, storedPath, forceTemplate, actor, actorDisplay, ip);
            } catch (Exception e) {
                log.error("Extraction pipeline failed for submission {}", saved.getId(), e);
                submissions.findById(saved.getId()).ifPresent(s -> {
                    s.setStatus("Error");
                    submissions.save(s);
                });
            }
        });

        return ResponseEntity.accepted().body(
            toDto(saved, facilityOpt.get().getName()));
    }

    // ── Extraction pipeline ──────────────────────────────────────────────────

    private record PipelineResult(ExtractionResponse extraction, ResolvedTemplate resolved) {}

    // Inspect → recognise → extract. pe-sub-extraction returns raw signals; TemplateRecognitionService
    // matches them against the bb_templates registry; the resolved definition is pushed back for a
    // deterministic parse. Returns a null extraction (with UNKNOWN resolved) when the engine is down.
    private PipelineResult runExtraction(int facilityId, Path filePath, String agentBank, String forcedTemplate) {
        WorkbookSignals signals = extractionClient.inspect(String.valueOf(facilityId), filePath);
        if (signals == null) {
            return new PipelineResult(null, ResolvedTemplate.unknown());
        }
        ResolvedTemplate resolved = recognitionService.recognize(signals, agentBank, forcedTemplate);
        log.info("Recognition facilityId={} agentBank='{}' forcedTemplate='{}' => recognized={} template='{}' sheet='{}' headerRow={} span={} sheetNames={} autoDiscover={} groups={} matchedBy='{}'",
            facilityId, agentBank, forcedTemplate, resolved.recognized(), resolved.templateName(),
            resolved.sheetName(), resolved.headerRowIndex(), resolved.headerRowSpan(),
            resolved.sheetNames(), resolved.autoDiscoverTabs(), resolved.groupMap().size(), resolved.matchedBy());
        ExtractionResponse extraction = extractionClient.extractResolved(
            String.valueOf(facilityId), filePath, agentBank, resolved);
        return new PipelineResult(extraction, resolved);
    }

    private void runExtractionPipeline(Submission sub, int facilityId, Path filePath, String forceTemplate,
                                       String userName, String userDisplay, String ip) {
        String forcedTemplate = normalizeForcedTemplate(forceTemplate);
        log.info("Starting extraction submission={} facilityId={} agentBank='{}' file='{}' forcedTemplate='{}'",
            sub.getId(), facilityId, sub.getAgentBank(), sub.getFileName(), forcedTemplate);

        PipelineResult pipeline = runExtraction(facilityId, filePath, sub.getAgentBank(), forcedTemplate);
        ExtractionResponse extraction = pipeline.extraction();

        if (extraction == null) {
            log.warn("Extraction skipped for submission {} — pe-sub-extraction unreachable", sub.getId());
            auditService.log("Extraction Failed",
                "Submission #" + sub.getId() + " extraction failed: pe-sub-extraction unreachable",
                facilityId, userName, userDisplay, ip);
            sub.setStatus("Error");
            submissions.save(sub);
            return;
        }

        storeExtractionResult(sub.getId(), extraction, forcedTemplate, pipeline.resolved());
        log.info("Stored extraction submission={} recognizedTemplate='{}' format='{}' rows={} mappings={} unrecognized={} forcedTemplate='{}'",
            sub.getId(), pipeline.resolved().templateName(),
            extraction.template() != null ? extraction.template().format() : null,
            extraction.records() != null ? extraction.records().size() : 0,
            extraction.fieldMappings() != null ? extraction.fieldMappings().size() : 0,
            extraction.unrecognizedColumns() != null ? extraction.unrecognizedColumns().size() : 0,
            forcedTemplate);
        auditService.log("Extraction Completed",
            extractionAuditDetail("Submission #" + sub.getId() + " extracted", extraction, forcedTemplate),
            facilityId, userName, userDisplay, ip);

        IngestRequest ingestRequest = toIngestRequest(facilityId, extraction);
        ingestService.ingest(sub.getId(), ingestRequest);

        sub.setStatus("Review");
        sub.setWizardStep(3);
        submissions.save(sub);

        // The owner is actively working the submission (extraction → matches → classification →
        // run) — that is 'In Progress', not 'Needs Review'. 'Needs Review' is reserved for a
        // submission a manager sent back (see reject()).
        facilities.findById(facilityId).ifPresent(f -> {
            f.setStatus("In Progress");
            facilities.save(f);
        });
    }

    private void storeExtractionResult(int submissionId, ExtractionResponse r, String forcedTemplate,
                                       ResolvedTemplate resolved) {
        SubmissionExtraction entity = extractionRepo.findBySubmissionId(submissionId)
            .orElse(new SubmissionExtraction());
        entity.setSubmissionId(submissionId);
        entity.setForcedTemplate(forcedTemplate);

        // Recognition now happens in pe-sub-api; the recognised fund template name is authoritative.
        String recognizedVersion = resolved != null && resolved.recognized()
            ? resolved.templateName()
            : (r.template() != null ? r.template().version() : null);
        if (r.template() != null) {
            entity.setTemplateFormat(r.template().format());
            entity.setTemplateVersion(recognizedVersion);
            entity.setHeaderRowIndex(r.template().headerRowIndex());
            entity.setSheetName(r.template().sheetName());
        } else {
            entity.setTemplateVersion(recognizedVersion);
        }

        // Build canonical field lookup from DB — keyed by extraction_key and canonical name.
        // The UI uses the ordered field list as its review-grid column set.
        List<FmCanonicalField> canonicalFields = canonicalFieldRepo.findAllByOrderByGroupSortAscFieldSortAsc();
        Map<String, FmCanonicalField> cfByKey = new HashMap<>();
        canonicalFields.forEach(cf -> {
            if (cf.getExtractionKey() != null) cfByKey.put(cf.getExtractionKey(), cf);
            cfByKey.put(cf.getCanonical(), cf);
        });

        // Build extracted_lps JSON array
        ArrayNode lpArray = mapper.createArrayNode();
        if (r.records() != null) {
            List<ObjectNode>    rows   = new ArrayList<>();
            List<BigDecimal>    bbRaws = new ArrayList<>();
            int seqId = 1;
            for (ExtractionResponse.ExtractedRecord rec : r.records()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("id",           seqId++);
                row.put("rowIndex",     rec.rowIndex());
                if (rec.fundSleeve() != null) row.put("fundSleeve", rec.fundSleeve());
                String investorName = fieldStr(rec.fields(), "INVESTOR_NAME");
                row.put("name",         investorName);
                String investorType = fieldStr(rec.fields(), "INVESTOR_TYPE");
                if (investorType.isBlank()) investorType = InvestorTypeDeriver.derive(investorName);
                row.put("investorType", investorType);
                String agentClass = fieldStr(rec.fields(), "AGENT_LP_CLASSIFICATION");
                String agentClsSource = !agentClass.isBlank() ? "EXTRACTED" : "";
                row.put("agentClass",   agentClass);
                row.put("agentClsSource", agentClsSource);
                row.put("commit",       fmtMoney(fieldDec(rec.fields(), "COMMITMENT")));
                row.put("uncalled",     fmtMoney(fieldDec(rec.fields(), "UNCALLED")));
                row.put("aum",          fmtMoneyOrRaw(rec.fields(), "AUM"));
                row.put("sizeMetricType", fieldStr(rec.fields(), "Size Metric Type"));
                row.put("sizeValueTier",  fieldStr(rec.fields(), "Size Value / Tier"));
                row.put("lpSizeCriteria", fieldStr(rec.fields(), "LP Size Criteria"));
                row.put("lpSizeBil",      fieldStr(rec.fields(), "LP Size ($ Bil)"));
                row.put("agentRate",    fmtRate(fieldDec(rec.fields(), "AGENT_RATE", "ADVANCE_RATE")));
                row.put("agentConc",    fmtRate(fieldDec(rec.fields(), "CONCENTRATION_LIMIT")));
                row.put("conf",         overallConf(rec));
                row.put("requiresReview", rec.requiresReview());
                row.put("parent",       fieldStr(rec.fields(), "Parent / Sponsor"));
                row.put("nav",          fmtMoneyOrRaw(rec.fields(), "NAV"));
                row.put("sp",           fieldStr(rec.fields(), "S&P Rating"));
                row.put("moodys",       fieldStr(rec.fields(), "Moody's Rating"));
                row.put("fitch",        fieldStr(rec.fields(), "Fitch Rating"));
                row.put("transferee",   fieldStr(rec.fields(), "Transferee"));
                row.put("notes",        fieldStr(rec.fields(), "NOTES"));
                row.put("calledCap",   fmtMoneyOrRaw(rec.fields(), "Called Capital"));
                row.put("pctCapCommit", fieldStr(rec.fields(), "% of Capital Commitments"));
                row.put("pctCalled",   fieldStr(rec.fields(), "% of LP Called"));
                row.put("pctUncalled", fieldStr(rec.fields(), "% of Uncalled Capital"));
                row.put("agentExcessConc", fmtMoneyOrRaw(rec.fields(), "Excess Concentration"));
                row.put("agentBB",     fieldStr(rec.fields(), "BORROWING_BASE", "Borrowing Base"));
                row.put("pctBB",       fieldStr(rec.fields(), "PCT_OF_BORROWING_BASE", "% of Borrowing Base"));
                ObjectNode canonicalValues = mapper.createObjectNode();
                for (FmCanonicalField cf : canonicalFields) {
                    String display = displayValueForCanonical(rec.fields(), cf);
                    if (!display.isBlank()) canonicalValues.put(cf.getCanonical(), display);
                }
                row.set("canonicalFields", canonicalValues);
                ArrayNode warnings = mapper.createArrayNode();
                if (rec.warnings() != null) {
                    rec.warnings().forEach(w -> warnings.add(w.field() + ": " + w.message()));
                }
                row.set("warnings", warnings);

                BigDecimal uncalledDec = fieldDec(rec.fields(), "UNCALLED");
                BigDecimal rateDec     = fieldDec(rec.fields(), "AGENT_RATE", "ADVANCE_RATE");
                BigDecimal rateNorm    = rateDec != null
                    ? (rateDec.compareTo(BigDecimal.ONE) < 0
                        ? rateDec
                        : rateDec.divide(BigDecimal.valueOf(100), 20, RoundingMode.HALF_UP))
                    : null;
                // Keep the full product — clipping the computed BB proxy to whole dollars
                // discards cents the agent's own column reports (e.g. "77334798.45").
                BigDecimal bbRaw = (uncalledDec != null && rateNorm != null)
                    ? uncalledDec.multiply(rateNorm)
                    : BigDecimal.ZERO;
                bbRaws.add(bbRaw);

                // C2: carry precise money (absolute dollars) alongside the rounded display strings
                // so the committed LP records — and the BB engine — read exact values. Agent BB
                // mirrors the display: the reported column when present, else the computed proxy.
                BigDecimal agentBbDec = fieldDec(rec.fields(), "BORROWING_BASE", "Borrowing Base");
                BigDecimal agentBbNum = agentBbDec != null ? agentBbDec
                    : (bbRaw.compareTo(BigDecimal.ZERO) > 0 ? bbRaw : null);
                putDec(row, "uncalledNum", uncalledDec);
                putDec(row, "commitNum",   fieldDec(rec.fields(), "COMMITMENT"));
                putDec(row, "aumNum",      fieldDec(rec.fields(), "AUM"));
                putDec(row, "agentBBNum",  agentBbNum);

                rows.add(row);
            }

            BigDecimal totalBB = bbRaws.stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b));
            for (int i = 0; i < rows.size(); i++) {
                ObjectNode row = rows.get(i);
                BigDecimal bb  = bbRaws.get(i);
                row.put("agentBBFmt", bb.compareTo(BigDecimal.ZERO) > 0 ? fmtMoney(bb) : "");
                String pct = "";
                if (totalBB.compareTo(BigDecimal.ZERO) > 0 && bb.compareTo(BigDecimal.ZERO) > 0) {
                    // Already a percent value (may legitimately be < 1%) — format directly
                    // rather than via fmtRate, whose <1 branch would re-multiply by 100.
                    // Rate convention: exactly one decimal.
                    pct = bb.multiply(BigDecimal.valueOf(100))
                        .divide(totalBB, 1, RoundingMode.HALF_UP)
                        .toPlainString() + "%";
                }
                row.put("pctBBFmt", pct);
                lpArray.add(row);
            }
        }
        entity.setTotalRows(lpArray.size());
        entity.setFlaggedCount(r.totalFlagged());
        entity.setExtractedLps(lpArray);

        // Build field_mappings JSON array
        ArrayNode fmArray = mapper.createArrayNode();
        if (r.fieldMappings() != null) {
            for (ExtractionResponse.FieldMappingEntry fm : r.fieldMappings()) {
                FmCanonicalField cf = cfByKey.get(fm.canonicalField());
                ObjectNode row = mapper.createObjectNode();
                row.put("extracted",  fm.extractedHeader());
                row.put("canonical",  cf != null ? cf.getGroupName() + " — " + cf.getCanonical() : fm.canonicalField());
                row.put("group",      cf != null ? cf.getGroupName() : fm.canonicalField());
                row.put("note",       confidenceNote(fm.confidence()));
                row.put("tier",       "Core");
                fmArray.add(row);
            }
        }
        entity.setFieldMappings(fmArray);

        // Build unrecognized_columns JSON array
        ArrayNode ucArray = mapper.createArrayNode();
        if (r.unrecognizedColumns() != null) {
            r.unrecognizedColumns().forEach(ucArray::add);
        }
        entity.setUnrecognizedColumns(ucArray);

        extractionRepo.save(entity);
    }

    // ── GET /api/submissions/:id/extracted-lps ───────────────────────────────

    @GetMapping("/{id}/extracted-lps")
    public ResponseEntity<?> extractedLPs(@PathVariable int id) {
        return extractionRepo.findBySubmissionId(id)
            .<ResponseEntity<?>>map(e -> ResponseEntity.ok(
                e.getExtractedLps() != null ? e.getExtractedLps() : mapper.createArrayNode()))
            .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /api/submissions/:id/extracted-lps/:rowId ────────────────────

    @DeleteMapping("/{id}/extracted-lps/{rowId}")
    public ResponseEntity<?> discardExtractedLp(@PathVariable int id, @PathVariable int rowId) {
        Optional<Submission> subOpt = submissions.findById(id);
        if (subOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!canModify(subOpt.get())) return notOwner();
        return extractionRepo.findBySubmissionId(id).<ResponseEntity<?>>map(e -> {
            if (!(e.getExtractedLps() instanceof ArrayNode arr)) return ResponseEntity.notFound().build();
            ArrayNode updated = mapper.createArrayNode();
            arr.forEach(node -> { if (node.path("id").asInt(-1) != rowId) updated.add(node); });
            e.setExtractedLps(updated);
            e.setTotalRows(updated.size());
            extractionRepo.save(e);
            return ResponseEntity.noContent().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── GET /api/submissions/:id/field-map ───────────────────────────────────

    @GetMapping("/{id}/field-map")
    public ResponseEntity<?> fieldMap(@PathVariable int id) {
        return extractionRepo.findBySubmissionId(id)
            .<ResponseEntity<?>>map(e -> ResponseEntity.ok(
                e.getFieldMappings() != null ? e.getFieldMappings() : mapper.createArrayNode()))
            .orElse(ResponseEntity.notFound().build());
    }

    // ── GET /api/submissions/:id/doc-recognition ─────────────────────────────

    @GetMapping("/{id}/doc-recognition")
    public ResponseEntity<?> docRecognition(@PathVariable int id) {
        Optional<Submission> subOpt = submissions.findById(id);
        if (subOpt.isEmpty()) return ResponseEntity.notFound().build();
        Optional<SubmissionExtraction> extOpt = extractionRepo.findBySubmissionId(id);
        if (extOpt.isEmpty()) return ResponseEntity.notFound().build();

        Submission sub = subOpt.get();
        SubmissionExtraction ext = extOpt.get();

        int total     = ext.getTotalRows();
        int mapped    = ext.getFieldMappings()       != null ? ext.getFieldMappings().size()       : 0;
        int unmatched = ext.getUnrecognizedColumns() != null ? ext.getUnrecognizedColumns().size() : 0;
        int hdrIdx    = ext.getHeaderRowIndex() != null ? ext.getHeaderRowIndex() : 0;
        int hdrRow1   = hdrIdx + 1;
        String hdrInfo = "Row " + hdrRow1 + " · " + mapped + " columns matched · " + unmatched + " unmatched";

        String sheetPart     = ext.getSheetName() != null ? " · Sheet: " + ext.getSheetName() : "";
        String tablesInfo    = "1 borrowing-base table" + sheetPart;
        String tableLocation = "Rows " + (hdrRow1 + 1) + "–" + (hdrRow1 + total);

        ObjectNode doc = mapper.createObjectNode();
        doc.put("document",         sub.getFileName());
        doc.put("format",           friendlyFormat(ext.getTemplateFormat(), ext.getTemplateVersion()));
        doc.put("tablesIdentified", tablesInfo);
        doc.put("tableLocation",    tableLocation);
        doc.put("headerRow",        hdrRow1);
        doc.put("totalRows",        total);
        doc.put("mappedColumns",    mapped);
        doc.put("unmatchedColumns", unmatched);
        doc.put("headerInfo",       hdrInfo);
        doc.put("forcedTemplate",   ext.getForcedTemplate() != null ? ext.getForcedTemplate() : "");

        return ResponseEntity.ok(doc);
    }

    // ── GET /api/submissions/:id/unrecognized-columns ────────────────────────

    @GetMapping("/{id}/unrecognized-columns")
    public ResponseEntity<?> unrecognizedColumns(@PathVariable int id) {
        return extractionRepo.findBySubmissionId(id)
            .<ResponseEntity<?>>map(e -> ResponseEntity.ok(
                e.getUnrecognizedColumns() != null ? e.getUnrecognizedColumns() : mapper.createArrayNode()))
            .orElse(ResponseEntity.notFound().build());
    }

    // ── POST /api/submissions/:id/reextract ─────────────────────────────────

    record ReextractRequest(String templateName) {}

    @Transactional
    @PostMapping("/{id}/reextract")
    public ResponseEntity<?> reextract(@PathVariable int id,
                                       @RequestBody(required = false) ReextractRequest body,
                                       HttpServletRequest request) {
        Optional<Submission> subOpt = submissions.findById(id);
        if (subOpt.isEmpty()) return ResponseEntity.notFound().build();
        Submission sub = subOpt.get();
        if (!canModify(sub)) return notOwner();
        if (sub.getFilePath() == null) {
            return ResponseEntity.badRequest().body("No stored file for this submission.");
        }

        // A templateName in the request forces that fund template; otherwise reuse any template
        // the operator previously forced for this submission so the choice survives re-extraction.
        String forcedTemplate = resolveForcedTemplate(id, body != null ? body.templateName() : null);
        log.info("Re-extract called for submission {} resolved forcedTemplate={}", id, forcedTemplate);

        log.info("Starting re-extraction submission={} facilityId={} agentBank='{}' file='{}' forcedTemplate='{}'",
            id, sub.getFacilityId(), sub.getAgentBank(), sub.getFileName(), forcedTemplate);
        PipelineResult pipeline = runExtraction(sub.getFacilityId(), Paths.get(sub.getFilePath()),
            sub.getAgentBank(), forcedTemplate);
        ExtractionResponse extraction = pipeline.extraction();
        if (extraction == null) {
            auditService.log("Re-extraction Failed",
                "Submission #" + id + " re-extraction failed: pe-sub-extraction unreachable",
                sub.getFacilityId(), currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
            return ResponseEntity.status(502).body("pe-sub-extraction unreachable.");
        }

        storeExtractionResult(id, extraction, forcedTemplate, pipeline.resolved());
        log.info("Stored re-extraction submission={} recognizedFormat='{}' recognizedVersion='{}' rows={} mappings={} unrecognized={} forcedTemplate='{}'",
            id,
            extraction.template() != null ? extraction.template().format() : null,
            extraction.template() != null ? extraction.template().version() : null,
            extraction.records() != null ? extraction.records().size() : 0,
            extraction.fieldMappings() != null ? extraction.fieldMappings().size() : 0,
            extraction.unrecognizedColumns() != null ? extraction.unrecognizedColumns().size() : 0,
            forcedTemplate);
        auditService.log("Re-extraction",
            extractionAuditDetail("Submission #" + id + " re-extracted", extraction, forcedTemplate),
            sub.getFacilityId(), currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        return ResponseEntity.noContent().build();
    }

    private String extractionAuditDetail(String prefix, ExtractionResponse extraction, String forcedTemplate) {
        ExtractionResponse.TemplateInfo template = extraction != null ? extraction.template() : null;
        String recognized = template != null ? friendlyFormat(template.format(), template.version()) : "Unknown template";
        StringBuilder detail = new StringBuilder(prefix)
            .append(": ")
            .append(recognized)
            .append(" · rows=")
            .append(extraction != null && extraction.records() != null ? extraction.records().size() : 0)
            .append(" · mappings=")
            .append(extraction != null && extraction.fieldMappings() != null ? extraction.fieldMappings().size() : 0)
            .append(" · unrecognized=")
            .append(extraction != null && extraction.unrecognizedColumns() != null ? extraction.unrecognizedColumns().size() : 0);
        if (template != null) {
            if (template.sheetName() != null && !template.sheetName().isBlank()) {
                detail.append(" · sheet=").append(template.sheetName());
            }
            detail.append(" · headerRow=").append(template.headerRowIndex());
        }
        if (forcedTemplate != null && !forcedTemplate.isBlank()) {
            detail.append(" · forced=").append(forcedTemplate);
        }
        return detail.toString();
    }

    // Resolves the forced template for a re-extraction: an explicit (non-blank) request value
    // wins and becomes the new forced choice; otherwise the previously persisted forced template
    // (if any) is reused so remap/discard re-extractions don't revert to auto-detection.
    private String resolveForcedTemplate(int submissionId, String requested) {
        String normalized = normalizeForcedTemplate(requested);
        if (normalized != null) return normalized;
        return extractionRepo.findBySubmissionId(submissionId)
            .map(ext -> {
                String forced = normalizeForcedTemplate(ext.getForcedTemplate());
                return forced != null
                    ? forced
                    : structuralTemplateFallback(ext.getTemplateVersion(), ext.getTemplateFormat());
            })
            .orElse(null);
    }

    private String normalizeForcedTemplate(String templateName) {
        return templateName != null && !templateName.isBlank() ? templateName.trim() : null;
    }

    private String structuralTemplateFallback(String templateVersion, String templateFormat) {
        String normalized = normalizeForcedTemplate(templateVersion);
        if (normalized == null) return null;
        if (templateFormat != null && normalized.equalsIgnoreCase(templateFormat)) return null;
        if (normalized.matches("(?i)v?\\d+(\\.\\d+)*")) return null;
        return normalized;
    }

    // ── POST /api/submissions/:id/remap ─────────────────────────────────────

    record RemapRequest(String extractedHeader, String canonical) {}

    @PostMapping("/{id}/remap")
    public ResponseEntity<?> remap(
            @PathVariable int id,
            @RequestBody RemapRequest body,
            HttpServletRequest request) {

        Optional<Submission> subOpt = submissions.findById(id);
        if (subOpt.isEmpty()) return ResponseEntity.notFound().build();
        Submission sub = subOpt.get();
        if (!canModify(sub)) return notOwner();
        if (sub.getFilePath() == null) {
            return ResponseEntity.badRequest().body("No stored file for this submission.");
        }

        Optional<FmCanonicalField> fieldOpt = canonicalFieldRepo.findByCanonical(body.canonical());
        if (fieldOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Unknown canonical field: " + body.canonical());
        }
        FmCanonicalField field = fieldOpt.get();

        List<FmAlias> existing = aliasRepo.findByCanonicalFieldIdOrderByAliasSortAsc(field.getId());
        boolean alreadyExists = existing.stream()
            .anyMatch(a -> a.getAliasText().equalsIgnoreCase(body.extractedHeader()));
        if (!alreadyExists) {
            int nextSort = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getAliasSort() + 1;
            FmAlias alias = new FmAlias();
            alias.setCanonicalFieldId(field.getId());
            alias.setAliasSort(nextSort);
            alias.setAliasText(body.extractedHeader());
            alias.setTier("Bank");
            alias.setBank(sub.getAgentBank());
            aliasRepo.save(alias);
            auditService.log("Field Mapping Change",
                "FM Alias Added: \"" + body.extractedHeader() + "\" → " + body.canonical()
                    + " (" + sub.getAgentBank() + ")",
                sub.getFacilityId(), currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        }

        String forcedTemplate = resolveForcedTemplate(id, null);
        log.info("Remap called for submission {} resolved forcedTemplate={}", id, forcedTemplate);
        PipelineResult pipeline = runExtraction(sub.getFacilityId(), Paths.get(sub.getFilePath()),
            sub.getAgentBank(), forcedTemplate);
        ExtractionResponse extraction = pipeline.extraction();
        if (extraction == null) {
            return ResponseEntity.status(502).body("pe-sub-extraction unreachable — alias saved, re-extraction pending.");
        }

        storeExtractionResult(id, extraction, forcedTemplate, pipeline.resolved());
        return ResponseEntity.ok().build();
    }

    // ── GET /api/submissions ─────────────────────────────────────────────────

    @GetMapping
    public List<SubmissionDto> list(@RequestParam(required = false) Integer facilityId) {
        List<Submission> subs = (facilityId != null
            ? submissions.findByFacilityIdOrderByCreatedAtDesc(facilityId)
            : submissions.findAllByOrderByCreatedAtDesc())
            .stream().filter(s -> !"Processing".equals(s.getStatus())).toList();

        Set<Integer> ids = subs.stream().map(sub -> sub.getFacilityId())
            .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, String> nameById = new HashMap<>();
        facilities.findAllById(new HashSet<>(ids)).forEach(f -> nameById.put(f.getId(), f.getName()));

        return subs.stream().map(s -> toDto(s, nameById.getOrDefault(s.getFacilityId(), "—"))).toList();
    }

    // ── GET /api/submissions/:id ─────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<SubmissionDto> get(@PathVariable int id) {
        return submissions.findById(id).map(s -> {
            Integer facilityId = s.getFacilityId();
            String facilityName = facilityId != null
                ? facilities.findById(facilityId).map(facility -> facility.getName()).orElse("—")
                : "—";
            return ResponseEntity.ok(toDto(s, facilityName));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /api/submissions/:id/confirm ────────────────────────────────────

    record ConfirmResponse(boolean templateSaved, String templateName) {}

    @Transactional
    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable int id, HttpServletRequest request) {
        Optional<Submission> subOpt = submissions.findById(id);
        if (subOpt.isEmpty()) return ResponseEntity.notFound().build();
        Submission sub = subOpt.get();
        if (!canModify(sub)) return notOwner();

        String agentBank = sub.getAgentBank();
        Optional<SubmissionExtraction> extOpt = extractionRepo.findBySubmissionId(id);

        // Confirmation must not mutate the controlled BB-template registry. Templates are added
        // only through the explicit /api/bb-templates create/import administration workflows.

        if (extOpt.isPresent()) {
            var entries = matchingService.buildMatchQueueEntries(
                id, sub.getFacilityId(), extOpt.get().getExtractedLps());
            matchQueueRepo.deleteBySubmissionId(id);
            matchQueueRepo.saveAll(entries);
        }

        sub.setWizardStep(4);
        submissions.save(sub);

        auditService.log("Extraction Confirmed", "Submission #" + id + " extraction confirmed",
            sub.getFacilityId(), currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        return ResponseEntity.ok(new ConfirmResponse(false, agentBank));
    }

    // ── PATCH /api/submissions/:id/shadow-bb-state ───────────────────────────
    // Advances the submission to wizard step 5 and optionally persists the LpRecord
    // classification/rate overrides set by the credit officer on the Run Shadow BB screen.
    // Called on "Commit Decisions" (no overrides yet) and on "Save" (with overrides).

    record ShadowBbStateRequest(tools.jackson.databind.JsonNode overrides) {}

    @Transactional
    @PatchMapping("/{id}/shadow-bb-state")
    public ResponseEntity<?> saveShadowBbState(
            @PathVariable int id,
            @RequestParam(value = "expectedVersion", required = false) Long expectedVersion,
            @RequestBody ShadowBbStateRequest req) {
        return submissions.findById(id).<ResponseEntity<?>>map(sub -> {
            if (!canModify(sub)) return notOwner();
            if (isStale(sub, expectedVersion)) return stale();
            // On first transition to step 5, commit accepted matches and rejected-as-new rows.
            if (sub.getWizardStep() < 5) {
                extractionRepo.findBySubmissionId(id).ifPresent(ext ->
                    ingestService.commitMatchQueueDecisions(id, sub.getFacilityId(), ext.getExtractedLps()));
            }
            sub.setWizardStep(5);
            if (req.overrides() != null) {
                sub.setShadowBbOverrides(req.overrides());
            }
            // Flush before building the DTO so Hibernate's @Version increment is included in the
            // response. The UI passes this token to /complete immediately after saving Step 5.
            submissions.saveAndFlush(sub);
            Integer facilityId = sub.getFacilityId();
            String facilityName = facilityId != null
                ? facilities.findById(facilityId).map(facility -> facility.getName()).orElse("—")
                : "—";
            return ResponseEntity.ok(toDto(sub, facilityName));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /api/submissions/:id/complete ──────────────────────────────────
    // Maker step: the operator submits the completed Shadow BB for independent review.
    // The facility does NOT go Active here — status → 'Pending Review' (step 6) and the maker's
    // identity is recorded. Only a Manager may accept or reject (see /accept, /reject).
    // Idempotent while already pending: re-submitting keeps the original maker identity.

    @Transactional
    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(
            @PathVariable int id,
            @RequestParam(value = "expectedVersion", required = false) Long expectedVersion,
            HttpServletRequest request) {
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission sub = opt.get();
        if (!canModify(sub)) return notOwner();
        if (isStale(sub, expectedVersion)) return stale();
        sub.setStatus(STATUS_PENDING_REVIEW);
        sub.setWizardStep(6);
        // Record the maker as the stable uuName (never a display name — RBAC_ROLES.md: names are
        // not authorization keys). reject() clears submitted_by, so on a re-submit after changes
        // this correctly re-stamps the current submitter rather than a stale original author.
        if (sub.getSubmittedBy() == null || sub.getSubmittedBy().isBlank()) {
            sub.setSubmittedBy(currentUser.uuName());
        }
        // A fresh review cycle: drop any prior rejection note/checker so the pending submission is
        // not shown as "Changes Requested" while it now again awaits review.
        sub.setReviewNote(null);
        sub.setReviewedBy(null);
        submissions.save(sub);

        Integer facilityId = sub.getFacilityId();
        // Facility rolls up to 'Pending Review' so the Dashboard can route a Manager to review it
        // and show the maker an "awaiting approval" state. Accept → Active; reject → Needs Review.
        String facilityName = "—";
        if (facilityId != null) {
            Optional<Facility> fOpt = facilities.findById(facilityId);
            if (fOpt.isPresent()) {
                Facility f = fOpt.get();
                f.setStatus("Pending Review");
                facilities.save(f);
                facilityName = f.getName();
            }
        }

        auditService.log("Shadow BB Submitted",
            "Submission #" + id + " submitted for independent review",
            facilityId, currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));

        return ResponseEntity.ok(toDto(sub, facilityName));
    }

    // ── POST /api/submissions/:id/accept ────────────────────────────────────
    // Checker step (Manager-only, enforced in SecurityConfig). Completes independent review:
    // submission → Processed (step 6), facility → Active, lastRunAt stamped, credit profile
    // written back to LP Master. A Manager may review their own submission so a facility is not
    // locked when no second reviewer is available.

    @Transactional
    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable int id, HttpServletRequest request) {
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission sub = opt.get();
        if (!STATUS_PENDING_REVIEW.equals(sub.getStatus())) {
            return ResponseEntity.status(409).body("Submission is not pending review.");
        }
        String checkerId = currentUser.uuName();

        sub.setStatus("Processed");
        sub.setWizardStep(6);
        sub.setReviewedBy(checkerId);
        submissions.save(sub);

        Integer facilityId = sub.getFacilityId();
        String facilityName = "—";
        if (facilityId != null) {
            Optional<Facility> fOpt = facilities.findById(facilityId);
            if (fOpt.isPresent()) {
                Facility f = fOpt.get();
                f.setStatus("Active");
                f.setLastRunAt(LocalDateTime.now());
                facilities.save(f);
                facilityName = f.getName();
            }
            // Write finalized UBS classification / rate / conc decisions back to LP Master
            // so future submissions for any facility benefit from this cycle's credit profile.
            lpMasterWriteBack.writeBack(facilityId);
        }

        auditService.log("Shadow BB Accepted",
            "Submission #" + id + " accepted by independent review",
            facilityId, currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        // Close the loop for the maker: the analyst learns their run was approved.
        notifier.broadcast("Shadow BB for " + facilityName + " was approved — the facility is now Active.");

        return ResponseEntity.ok(toDto(sub, facilityName));
    }

    // ── POST /api/submissions/:id/reject ────────────────────────────────────
    // Checker step (Manager-only). Returns the submission to an actionable state (step 5) with a
    // required reviewer rationale; the facility is left unchanged. A Manager may reject their own
    // submission so the workflow remains actionable when no second reviewer is available.

    record RejectRequest(String reason) {}

    @Transactional
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable int id,
                                    @RequestBody(required = false) RejectRequest body,
                                    HttpServletRequest request) {
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission sub = opt.get();
        if (!STATUS_PENDING_REVIEW.equals(sub.getStatus())) {
            return ResponseEntity.status(409).body("Submission is not pending review.");
        }
        String reason = body != null && body.reason() != null ? body.reason().trim() : "";
        if (reason.isEmpty()) {
            return ResponseEntity.badRequest().body("A rejection reason is required.");
        }
        String checkerId = currentUser.uuName();

        // Return to the actionable step. reviewNote present (with the submission back at step 5)
        // is the "Changes Requested" signal the analyst sees; submitted_by is cleared so the next
        // /complete re-stamps whoever actually re-submits.
        sub.setStatus("Review");
        sub.setWizardStep(5);
        sub.setReviewedBy(checkerId);
        sub.setReviewNote(reason);
        sub.setSubmittedBy(null);
        submissions.save(sub);

        Integer facilityId = sub.getFacilityId();
        // Facility returns to 'Needs Review' — the analyst must revise and re-run this cycle.
        String facilityName = "—";
        if (facilityId != null) {
            Optional<Facility> fOpt = facilities.findById(facilityId);
            if (fOpt.isPresent()) {
                Facility f = fOpt.get();
                f.setStatus("Needs Review");
                facilities.save(f);
                facilityName = f.getName();
            }
        }
        auditService.log("Shadow BB Rejected",
            "Submission #" + id + " rejected by independent review: " + reason,
            facilityId, currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        // Close the loop for the maker: the analyst learns changes were requested, and why.
        notifier.broadcast("Changes requested on " + facilityName + ": " + reason);

        return ResponseEntity.ok(toDto(sub, facilityName));
    }

    // ── POST /api/submissions/:id/take-over ──────────────────────────────────
    // Ownership handoff: another analyst (or a Manager reassigning) claims an in-flight submission
    // so they can edit it. This is the *controlled* alternative to two analysts silently clobbering
    // the same work — after takeover the previous owner is read-only (their writes 403) and is
    // notified. Terminal submissions (Processed / Aborted) cannot be taken over.

    @Transactional
    @PostMapping("/{id}/take-over")
    public ResponseEntity<?> takeOver(@PathVariable int id, HttpServletRequest request) {
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission sub = opt.get();
        if ("Processed".equals(sub.getStatus()) || "Aborted".equals(sub.getStatus())) {
            return ResponseEntity.status(409).body("A completed or aborted submission cannot be taken over.");
        }

        String newOwnerUuName = currentUser.uuName();
        String previousOwner  = sub.getOwnerUuName();
        String previousName   = sub.getOwnerName();
        if (newOwnerUuName != null && newOwnerUuName.equals(previousOwner)) {
            // Already the owner — nothing to do.
            return ResponseEntity.ok(toDto(sub, facilityNameOf(sub.getFacilityId())));
        }

        sub.setOwnerUuName(newOwnerUuName);
        sub.setOwnerName(currentUser.displayName());
        submissions.save(sub);

        String facilityName = facilityNameOf(sub.getFacilityId());
        auditService.log("Submission Taken Over",
            "Submission #" + id + " ownership taken over from " + (previousName != null ? previousName : "—")
                + " (" + (previousOwner != null ? previousOwner : "unassigned") + ")",
            sub.getFacilityId(), currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(request));
        // Notify the previous owner: their in-flight edits can no longer be saved.
        if (previousOwner != null && !previousOwner.isBlank()) {
            notifier.broadcast(currentUser.displayName() + " has taken over the "
                + facilityName + " submission. Your unsaved changes there can no longer be saved.");
        }

        return ResponseEntity.ok(toDto(sub, facilityName));
    }

    private String facilityNameOf(Integer facilityId) {
        return facilityId != null
            ? facilities.findById(facilityId).map(f -> f != null ? f.getName() : "—").orElse("—")
            : "—";
    }

    // ── POST /api/submissions/:id/abort ──────────────────────────────────────

    @Transactional
    @PostMapping("/{id}/abort")
    public ResponseEntity<?> abort(@PathVariable int id, HttpServletRequest request) {
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission sub = opt.get();
        // Ownership: only the owner (uploader) or a Manager may abort; other analysts have
        // read-only access to a colleague's submission (RBAC_ROLES.md). Legacy rows without a
        // recorded owner are not blocked.
        String owner = sub.getOwnerUuName();
        if (owner != null && !owner.isBlank() && !owner.equals(currentUser.uuName()) && !isManager()) {
            return ResponseEntity.status(403).body("Only the submission owner or a Manager may abort it.");
        }
        if ("Processed".equals(sub.getStatus())) {
            return ResponseEntity.status(409).body("Cannot abort a processed submission.");
        }
        if ("Aborted".equals(sub.getStatus())) {
            return ResponseEntity.status(409).body("Submission is already aborted.");
        }

        // Delete uploaded file from disk
        if (sub.getFilePath() != null) {
            try { Files.deleteIfExists(Paths.get(sub.getFilePath())); }
            catch (Exception e) { log.warn("Could not delete file for submission {}: {}", id, e.getMessage()); }
        }

        // Delete dependent records
        extractionRepo.deleteBySubmissionId(id);
        matchQueueRepo.deleteBySubmissionId(id);

        sub.setStatus("Aborted");
        submissions.save(sub);

        // Reset facility status if no remaining Review/Processing submissions for this facility
        Integer facilityId = sub.getFacilityId();
        boolean hasActiveReview = facilityId != null && submissions.findByFacilityIdOrderByCreatedAtDesc(facilityId)
            .stream()
            .filter(s -> !Objects.equals(s.getId(), id))
            .anyMatch(s -> "Review".equals(s.getStatus()) || "Processing".equals(s.getStatus()));
        if (!hasActiveReview && facilityId != null) {
            facilities.findById(facilityId).ifPresent(f -> {
                f.setStatus("Active");
                facilities.save(f);
            });
        }

        auditService.log("Abort", "Submission #" + id + " aborted", facilityId, currentUser.uuName(),
            currentUser.auditDisplayName(), auditService.extractIp(request));

        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** True when the authenticated principal holds the Manager authority. */
    private boolean isManager() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
    }

    /**
     * True when the caller may mutate this submission: the current owner, a Manager, or a legacy
     * submission with no recorded owner. This is the concurrency guard — a non-owner analyst has
     * read-only access and must take the submission over before editing it, which prevents two
     * analysts from clobbering the same work.
     */
    private boolean canModify(Submission sub) {
        String owner = sub.getOwnerUuName();
        return owner == null || owner.isBlank() || owner.equals(currentUser.uuName()) || isManager();
    }

    /** 403 (as ProblemDetail, so the SPA surfaces a friendly message) when a non-owner mutates. */
    private ResponseEntity<ProblemDetail> notOwner() {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
            "This submission is owned by another analyst. Take it over before editing.");
        pd.setTitle("Read-only");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    /**
     * Optimistic-concurrency check: true when the caller's expected version is stale (the submission
     * was changed since they loaded it — e.g. the same submission edited in another tab). The caller
     * returns {@link #stale()} on true.
     */
    private boolean isStale(Submission sub, Long expectedVersion) {
        return expectedVersion != null && !expectedVersion.equals(sub.getVersion());
    }

    /** 409 (as ProblemDetail) when a write is based on a stale version. */
    private ResponseEntity<ProblemDetail> stale() {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "This submission was changed elsewhere since you loaded it. Reload it before saving to avoid overwriting newer work.");
        pd.setTitle("Conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    private SubmissionDto toDto(Submission s, String facilityName) {
        return new SubmissionDto(
            s.getId(), s.getFacilityId(), facilityName,
            s.getAgentBank(), s.getPeriodMonth(), s.getStatus(),
            s.getFileName(), s.getUploadedBy(), s.getNotes(),
            s.getWizardStep(), s.getVersion(), s.getShadowBbOverrides(),
            s.getOwnerUuName(), s.getOwnerName(),
            s.getSubmittedBy(), s.getReviewedBy(), s.getReviewNote(),
            s.getCreatedAt(), s.getUpdatedAt()
        );
    }

    private IngestRequest toIngestRequest(int facilityId, ExtractionResponse r) {
        IngestRequest.TemplateInfo tmpl = r.template() != null
            ? new IngestRequest.TemplateInfo(
                r.template().format(), r.template().version(), r.template().headerRowIndex())
            : new IngestRequest.TemplateInfo("UNKNOWN", null, 0);

        List<IngestRequest.ExtractedLpRow> rows = r.records() != null
            ? r.records().stream().map(this::toIngestRow).toList()
            : List.of();

        return new IngestRequest(facilityId,
            new IngestRequest.ExtractionPayload(tmpl, rows, r.totalFlagged()));
    }

    private IngestRequest.ExtractedLpRow toIngestRow(ExtractionResponse.ExtractedRecord rec) {
        Map<String, ExtractionResponse.FieldValue> fields = rec.fields() != null ? rec.fields() : Map.of();
        return new IngestRequest.ExtractedLpRow(
            rec.rowIndex(),
            toStringField(fields.get("INVESTOR_NAME")),
            toDecimalFieldFromStr(fields.get("COMMITMENT")),
            toDecimalFieldFromStr(fields.get("UNCALLED")),
            toDecimalFieldFromStr(fields.get("AUM")),
            toDecimalFieldFromStr(fieldValue(fields, "AGENT_RATE", "ADVANCE_RATE")),
            toDecimalFieldFromStr(fields.get("CONCENTRATION_LIMIT")),
            toStringField(fields.get("INVESTOR_TYPE")),
            toStringField(fields.get("S&P Rating")),
            toStringField(fields.get("Moody's Rating")),
            toStringField(fields.get("Fitch Rating")),
            toStringField(fields.get("NAV")),
            toStringField(fields.get("AGENT_LP_CLASSIFICATION")),
            agentClsSourceField(fields.get("AGENT_LP_CLASSIFICATION")),
            toStringField(fields.get("Parent / Sponsor")),
            toStringField(fields.get("NOTES")),
            toStringField(fields.get("Transferee")),
            rec.requiresReview(),
            rec.warnings() != null
                ? rec.warnings().stream()
                    .map(w -> new IngestRequest.WarningEntry(w.field(), w.message(), w.rowIndex()))
                    .toList()
                : List.of()
        );
    }

    private IngestRequest.StringField toStringField(ExtractionResponse.FieldValue f) {
        return f != null ? new IngestRequest.StringField(f.value(), f.confidence(), f.sourceHeader()) : null;
    }

    private IngestRequest.StringField agentClsSourceField(ExtractionResponse.FieldValue f) {
        return f != null ? new IngestRequest.StringField("EXTRACTED", 1.0, f.sourceHeader()) : null;
    }

    private IngestRequest.DecimalField toDecimalFieldFromStr(ExtractionResponse.FieldValue f) {
        if (f == null) return null;
        return new IngestRequest.DecimalField(parseNumericSafe(f.value()), f.confidence(), f.sourceHeader());
    }

    // Writes a precise numeric (absolute dollars) into the row JSON, omitting it when null so the
    // consumer's display-string fallback still applies (C2).
    private static void putDec(ObjectNode row, String key, BigDecimal value) {
        if (value != null) row.put(key, value);
    }

    private String fieldStr(Map<String, ExtractionResponse.FieldValue> fields, String... keys) {
        ExtractionResponse.FieldValue f = fieldValue(fields, keys);
        return (f != null && f.value() != null) ? f.value() : "";
    }

    private BigDecimal fieldDec(Map<String, ExtractionResponse.FieldValue> fields, String... keys) {
        ExtractionResponse.FieldValue f = fieldValue(fields, keys);
        return f != null ? parseNumericSafe(f.value()) : null;
    }

    private ExtractionResponse.FieldValue fieldValue(Map<String, ExtractionResponse.FieldValue> fields, String... keys) {
        if (fields == null || fields.isEmpty() || keys == null) return null;
        for (String key : keys) {
            ExtractionResponse.FieldValue f = fields.get(key);
            if (f != null) return f;
        }
        return null;
    }

    private BigDecimal parseNumericSafe(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.trim();
            boolean accountingNegative = cleaned.startsWith("(") && cleaned.endsWith(")");
            if (accountingNegative) cleaned = cleaned.substring(1, cleaned.length() - 1);
            boolean trailingMinus = cleaned.endsWith("-");
            if (trailingMinus) cleaned = cleaned.substring(0, cleaned.length() - 1);
            cleaned = cleaned.replaceAll("[,$%]", "").replaceAll("\\s+", "");
            if (cleaned.isEmpty()) return null;
            char suffix = Character.toUpperCase(cleaned.charAt(cleaned.length() - 1));
            BigDecimal multiplier = switch (suffix) {
                case 'B' -> new BigDecimal("1000000000");
                case 'M' -> new BigDecimal("1000000");
                case 'K' -> new BigDecimal("1000");
                default  -> null;
            };
            if (multiplier != null) {
                String numeric = cleaned.substring(0, cleaned.length() - 1);
                BigDecimal value = new BigDecimal(numeric).multiply(multiplier);
                return accountingNegative || trailingMinus ? value.negate() : value;
            }
            BigDecimal value = new BigDecimal(cleaned);
            return accountingNegative || trailingMinus ? value.negate() : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int overallConf(ExtractionResponse.ExtractedRecord rec) {
        if (rec.fields() == null || rec.fields().isEmpty()) return 0;
        double sum = 0; int count = 0;
        for (ExtractionResponse.FieldValue f : rec.fields().values()) {
            if (f != null && f.confidence() > 0) { sum += f.confidence(); count++; }
        }
        return count > 0 ? (int) Math.round((sum / count) * 100) : 0;
    }

    private String displayValueForCanonical(Map<String, ExtractionResponse.FieldValue> fields, FmCanonicalField cf) {
        if (fields == null || fields.isEmpty()) return "";
        String key = cf.getExtractionKey();
        ExtractionResponse.FieldValue f = key != null ? fields.get(key) : null;
        if (f == null) f = fields.get(cf.getCanonical());
        if (f == null || f.value() == null || f.value().isBlank()) return "";

        BigDecimal dec = parseNumericSafe(f.value());
        if (dec != null && moneyCanonical(cf.getCanonical())) return fmtMoney(dec);
        if (dec != null && percentCanonical(cf.getCanonical())) return fmtRate(dec);
        return f.value();
    }

    private boolean moneyCanonical(String canonical) {
        return switch (canonical) {
            case "Capital Commitments", "Called Capital", "Recallable Distributions",
                 "Uncalled Capital", "AUM", "NAV", "Net Worth", "Pension Assets",
                 "Eligible Commitment", "Borrowing Base", "Excess Concentration" -> true;
            default -> false;
        };
    }

    private boolean percentCanonical(String canonical) {
        return canonical.contains("%")
            || canonical.equals("Advance Rate")
            || canonical.equals("Concentration Limit");
    }

    // For fields like AUM/NAV that may contain range strings (">$2B", "<$500M"), falls back
    // to the raw extracted string when numeric parsing fails, preserving the range notation.
    private String fmtMoneyOrRaw(Map<String, ExtractionResponse.FieldValue> fields, String key) {
        BigDecimal dec = fieldDec(fields, key);
        if (dec != null) return fmtMoney(dec);
        String raw = fieldStr(fields, key);
        return raw.isBlank() ? "" : raw;
    }

    // Full-precision dollar display: thousands grouping, every digit kept, no unit
    // abbreviation — "$1,999,999" must never render (or persist) as "$2.0M".
    private String fmtMoney(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        DecimalFormat money = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        money.setMaximumFractionDigits(20);
        return "$" + money.format(v);
    }

    // Rate display convention: exactly one decimal ("75.0%", "5.6%") — never a bare
    // integer percent and never more than one decimal.
    private String fmtRate(BigDecimal v) {
        if (v == null) return "";
        BigDecimal pct = v.compareTo(BigDecimal.ONE) < 0
            ? v.multiply(BigDecimal.valueOf(100)) : v;
        return pct.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String confidenceNote(double confidence) {
        if (confidence >= 1.0)  return "Exact match";
        if (confidence >= 0.900) {
            return "Auto-suggested via Jaro-Winkler score "
                + String.format(Locale.US, "%.3f", confidence)
                + " against the Field Mapping Dictionary; review mapping";
        }
        return "Matched via alias dictionary";
    }

    // The label is the recognised template name (Template ID) from the registry. No agent-bank or
    // fund names are hardcoded here — named content comes only from template contents or user entry.
    // {@code fmt} is retained for call-site compatibility but is no longer used (always UNKNOWN).
    private String friendlyFormat(String fmt, String recognizedTemplate) {
        if (recognizedTemplate != null && !recognizedTemplate.isBlank()) {
            return "Excel Workbook — " + recognizedTemplate + " template";
        }
        return "Excel Workbook — unrecognized template";
    }
}
