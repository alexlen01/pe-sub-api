package com.ubs.pesubapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ubs.pesubapi.dto.ExtractionResponse;
import com.ubs.pesubapi.dto.IngestRequest;
import com.ubs.pesubapi.entity.BbTemplate;
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
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.ExtractionClientService;
import com.ubs.pesubapi.service.LpIngestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    record SubmissionDto(
        Integer id, Integer facilityId, String facilityName,
        String agentBank, String periodMonth, String status,
        String fileName, Integer uploadedBy, String notes,
        LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    // ── Canonical field display metadata for field-map response ──────────────
    record FieldMeta(String canonical, String group) {}

    private static final Map<String, FieldMeta> CANONICAL_META = Map.ofEntries(
        // Extractable fields — keyed by extraction_key
        Map.entry("INVESTOR_NAME",       new FieldMeta("Identity & Classification — Investor Name",           "Identity & Classification")),
        Map.entry("LP_CLASSIFICATION",   new FieldMeta("Identity & Classification — LP Classification",      "Identity & Classification")),
        Map.entry("ELIGIBILITY_FLAG",    new FieldMeta("Identity & Classification — Eligibility Flag",       "Identity & Classification")),
        Map.entry("COMMITMENT",          new FieldMeta("Commitment Data — Capital Commitments",               "Commitment Data")),
        Map.entry("RECALLABLE_DIST",     new FieldMeta("Commitment Data — Recallable Distributions",         "Commitment Data")),
        Map.entry("UNCALLED",            new FieldMeta("Uncalled Data — Uncalled Capital",                    "Uncalled Data")),
        Map.entry("AUM",                 new FieldMeta("Financial Scale — AUM",                               "Financial Scale")),
        Map.entry("NAV",                 new FieldMeta("Financial Scale — NAV",                               "Financial Scale")),
        Map.entry("AGENT_RATE",          new FieldMeta("Borrowing Base — Agent Advance Rate",                 "Borrowing Base")),
        Map.entry("CONCENTRATION_LIMIT", new FieldMeta("Concentration — Agent Concentration Limit",           "Concentration")),
        // Non-extractable fields — keyed by canonical name
        Map.entry("Transferee",                new FieldMeta("Identity & Classification — Transferee",        "Identity & Classification")),
        Map.entry("Parent / Sponsor",          new FieldMeta("Identity & Classification — Parent / Sponsor",  "Identity & Classification")),
        Map.entry("% of Capital Commitments",  new FieldMeta("Commitment Data — % of Capital Commitments",   "Commitment Data")),
        Map.entry("Called Capital",            new FieldMeta("Commitment Data — Called Capital",              "Commitment Data")),
        Map.entry("% of Uncalled Capital",     new FieldMeta("Uncalled Data — % of Uncalled Capital",        "Uncalled Data")),
        Map.entry("% of LP Called",            new FieldMeta("Uncalled Data — % of LP Called",               "Uncalled Data")),
        Map.entry("Pension Assets",            new FieldMeta("Financial Scale — Pension Assets",             "Financial Scale")),
        Map.entry("Pension Funded %",          new FieldMeta("Financial Scale — Pension Funded %",           "Financial Scale")),
        Map.entry("Agent Eligible Commitment", new FieldMeta("Borrowing Base — Agent Eligible Commitment",   "Borrowing Base")),
        Map.entry("% of Eligible Uncalled",    new FieldMeta("Borrowing Base — % of Eligible Uncalled",      "Borrowing Base")),
        Map.entry("% of Borrowing Base",       new FieldMeta("Borrowing Base — % of Borrowing Base",         "Borrowing Base")),
        Map.entry("Agent Borrowing Base",      new FieldMeta("Borrowing Base — Agent Borrowing Base",        "Borrowing Base")),
        Map.entry("Excess Concentration",      new FieldMeta("Concentration — Excess Concentration",         "Concentration")),
        Map.entry("S&P Rating",                new FieldMeta("Ratings — S&P Rating",                        "Ratings")),
        Map.entry("Moody's Rating",            new FieldMeta("Ratings — Moody's Rating",                    "Ratings")),
        Map.entry("Fitch Rating",              new FieldMeta("Ratings — Fitch Rating",                      "Ratings")),
        Map.entry("S&P Numeric Score",         new FieldMeta("Ratings — S&P Numeric Score",                 "Ratings")),
        Map.entry("Moody's Numeric Score",     new FieldMeta("Ratings — Moody's Numeric Score",             "Ratings")),
        Map.entry("Agent Numeric Rating",      new FieldMeta("Ratings — Agent Numeric Rating",              "Ratings"))
    );

    private final SubmissionRepository           submissions;
    private final FacilityRepository             facilities;
    private final AuditLogService                auditService;
    private final ExtractionClientService        extractionClient;
    private final LpIngestService                ingestService;
    private final SubmissionExtractionRepository extractionRepo;
    private final MatchQueueEntryRepository      matchQueueRepo;
    private final FmCanonicalFieldRepository     canonicalFieldRepo;
    private final FmAliasRepository              aliasRepo;
    private final BbTemplateRepository           templateRepo;
    private final ObjectMapper                   mapper;

    @Value("${app.uploads.path:C:/Users/alexl/apps/pe-sub/uploads}")
    private String uploadsPath;

    public SubmissionController(SubmissionRepository submissions,
                                FacilityRepository facilities,
                                AuditLogService auditService,
                                ExtractionClientService extractionClient,
                                LpIngestService ingestService,
                                SubmissionExtractionRepository extractionRepo,
                                MatchQueueEntryRepository matchQueueRepo,
                                FmCanonicalFieldRepository canonicalFieldRepo,
                                FmAliasRepository aliasRepo,
                                BbTemplateRepository templateRepo,
                                ObjectMapper mapper) {
        this.submissions        = submissions;
        this.facilities         = facilities;
        this.auditService       = auditService;
        this.extractionClient   = extractionClient;
        this.ingestService      = ingestService;
        this.extractionRepo     = extractionRepo;
        this.matchQueueRepo     = matchQueueRepo;
        this.canonicalFieldRepo = canonicalFieldRepo;
        this.aliasRepo          = aliasRepo;
        this.templateRepo       = templateRepo;
        this.mapper             = mapper;
    }

    private record TemplateHints(String sheetName, Integer headerRowIndex) {}

    private TemplateHints hintsFor(String agentBank) {
        return templateRepo.findByAgentBankIgnoreCase(agentBank)
            .map(t -> new TemplateHints(t.getSheetName(), t.getHeaderRowIndex()))
            .orElse(new TemplateHints(null, null));
    }

    // ── POST /api/submissions ────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(
            @RequestParam("facilityId")           int           facilityId,
            @RequestParam("agentBank")            String        agentBank,
            @RequestParam("periodMonth")          String        periodMonth,
            @RequestParam("file")                 MultipartFile file,
            @RequestParam(value = "notes", required = false) String notes,
            HttpServletRequest request
    ) throws IOException {
        Optional<Facility> facilityOpt = facilities.findById(facilityId);
        if (facilityOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Facility " + facilityId + " not found.");
        }

        String original   = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.xlsx";
        String storedName = UUID.randomUUID() + "_" + original;
        Path   dir        = Paths.get(uploadsPath);
        Files.createDirectories(dir);
        Path storedPath = dir.resolve(storedName);
        Files.copy(file.getInputStream(), storedPath, StandardCopyOption.REPLACE_EXISTING);

        Submission sub = new Submission();
        sub.setFacilityId(facilityId);
        sub.setAgentBank(agentBank);
        sub.setPeriodMonth(periodMonth);
        sub.setFileName(original);
        sub.setFilePath(storedPath.toString());
        if (notes != null && !notes.isBlank()) sub.setNotes(notes.trim());
        Submission saved = submissions.save(sub);

        String detail = periodMonth + " · " + original + " · " + agentBank;
        auditService.log("Upload", detail, facilityId, "J. Smith", auditService.extractIp(request));

        try {
            runExtractionPipeline(saved, facilityId, storedPath);
        } catch (Exception e) {
            log.error("Extraction pipeline failed for submission {}", saved.getId(), e);
        }

        return ResponseEntity.status(201).body(
            toDto(saved, facilityOpt.get().getName()));
    }

    // ── Extraction pipeline ──────────────────────────────────────────────────

    private void runExtractionPipeline(Submission sub, int facilityId, Path filePath) {
        TemplateHints hints = hintsFor(sub.getAgentBank());
        ExtractionResponse extraction =
            extractionClient.extract(String.valueOf(facilityId), filePath,
                hints.sheetName(), hints.headerRowIndex());

        if (extraction == null) {
            log.warn("Extraction skipped for submission {} — pe-sub-extraction unreachable", sub.getId());
            sub.setStatus("Error");
            submissions.save(sub);
            return;
        }

        storeExtractionResult(sub.getId(), extraction);

        IngestRequest ingestRequest = toIngestRequest(facilityId, extraction);
        ingestService.ingest(sub.getId(), ingestRequest);

        sub.setStatus("Review");
        submissions.save(sub);

        facilities.findById(facilityId).ifPresent(f -> {
            f.setStatus("Needs Review");
            facilities.save(f);
        });
    }

    private void storeExtractionResult(int submissionId, ExtractionResponse r) {
        SubmissionExtraction entity = extractionRepo.findBySubmissionId(submissionId)
            .orElse(new SubmissionExtraction());
        entity.setSubmissionId(submissionId);

        if (r.template() != null) {
            entity.setTemplateFormat(r.template().format());
            entity.setTemplateVersion(r.template().version());
            entity.setHeaderRowIndex(r.template().headerRowIndex());
            entity.setSheetName(r.template().sheetName());
        }

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
                row.put("name",         fieldStr(rec.fields(), "INVESTOR_NAME"));
                row.put("agentClass",   fieldStr(rec.fields(), "LP_CLASSIFICATION"));
                row.put("commit",       fmtMoney(fieldDec(rec.fields(), "COMMITMENT")));
                row.put("uncalled",     fmtMoney(fieldDec(rec.fields(), "UNCALLED")));
                row.put("aum",          fmtMoney(fieldDec(rec.fields(), "AUM")));
                row.put("agentRate",    fmtRate(fieldDec(rec.fields(), "AGENT_RATE")));
                row.put("agentConc",    fmtRate(fieldDec(rec.fields(), "CONCENTRATION_LIMIT")));
                row.put("conf",         overallConf(rec));
                row.put("requiresReview", rec.requiresReview());
                row.put("parent",       fieldStr(rec.fields(), "Parent / Sponsor"));
                row.put("nav",          fmtMoney(fieldDec(rec.fields(), "NAV")));
                row.put("sp",           fieldStr(rec.fields(), "S&P Rating"));
                row.put("moodys",       fieldStr(rec.fields(), "Moody's Rating"));
                row.put("fitch",        fieldStr(rec.fields(), "Fitch Rating"));
                row.put("transferee",   fieldStr(rec.fields(), "Transferee"));
                ArrayNode warnings = mapper.createArrayNode();
                if (rec.warnings() != null) {
                    rec.warnings().forEach(w -> warnings.add(w.field() + ": " + w.message()));
                }
                row.set("warnings", warnings);

                BigDecimal uncalledDec = fieldDec(rec.fields(), "UNCALLED");
                BigDecimal rateDec     = fieldDec(rec.fields(), "AGENT_RATE");
                BigDecimal rateNorm    = rateDec != null
                    ? (rateDec.compareTo(BigDecimal.ONE) < 0
                        ? rateDec
                        : rateDec.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))
                    : null;
                BigDecimal bbRaw = (uncalledDec != null && rateNorm != null)
                    ? uncalledDec.multiply(rateNorm).setScale(0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
                bbRaws.add(bbRaw);
                rows.add(row);
            }

            BigDecimal totalBB = bbRaws.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            for (int i = 0; i < rows.size(); i++) {
                ObjectNode row = rows.get(i);
                BigDecimal bb  = bbRaws.get(i);
                row.put("agentBBFmt", bb.compareTo(BigDecimal.ZERO) > 0
                    ? "$" + String.format("%,.0f", bb) : "");
                String pct = "";
                if (totalBB.compareTo(BigDecimal.ZERO) > 0 && bb.compareTo(BigDecimal.ZERO) > 0) {
                    pct = bb.multiply(BigDecimal.valueOf(100))
                             .divide(totalBB, 2, RoundingMode.HALF_UP)
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
                FieldMeta meta = CANONICAL_META.get(fm.canonicalField());
                ObjectNode row = mapper.createObjectNode();
                row.put("extracted",  fm.extractedHeader());
                row.put("canonical",  meta != null ? meta.canonical() : fm.canonicalField());
                row.put("group",      meta != null ? meta.group()     : "Other");
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
        doc.put("format",           friendlyFormat(ext.getTemplateFormat()));
        doc.put("tablesIdentified", tablesInfo);
        doc.put("tableLocation",    tableLocation);
        doc.put("headerRow",        hdrRow1);
        doc.put("totalRows",        total);
        doc.put("mappedColumns",    mapped);
        doc.put("unmatchedColumns", unmatched);
        doc.put("headerInfo",       hdrInfo);

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

    @Transactional
    @PostMapping("/{id}/reextract")
    public ResponseEntity<?> reextract(@PathVariable int id, HttpServletRequest request) {
        Optional<Submission> subOpt = submissions.findById(id);
        if (subOpt.isEmpty()) return ResponseEntity.notFound().build();
        Submission sub = subOpt.get();
        if (sub.getFilePath() == null) {
            return ResponseEntity.badRequest().body("No stored file for this submission.");
        }

        TemplateHints hints = hintsFor(sub.getAgentBank());
        ExtractionResponse extraction =
            extractionClient.extract(String.valueOf(sub.getFacilityId()), Paths.get(sub.getFilePath()),
                hints.sheetName(), hints.headerRowIndex());
        if (extraction == null) {
            return ResponseEntity.status(502).body("pe-sub-extraction unreachable.");
        }

        storeExtractionResult(id, extraction);
        auditService.log("Re-extraction", "Submission #" + id + " re-extracted",
            sub.getFacilityId(), "J. Smith", auditService.extractIp(request));
        return ResponseEntity.noContent().build();
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
            alias.setTier("User");
            aliasRepo.save(alias);
            auditService.log("Field Mapping Change",
                "FM Alias Added: \"" + body.extractedHeader() + "\" → " + body.canonical(),
                sub.getFacilityId(), "J. Smith", auditService.extractIp(request));
        }

        TemplateHints hints = hintsFor(sub.getAgentBank());
        ExtractionResponse extraction =
            extractionClient.extract(String.valueOf(sub.getFacilityId()), Paths.get(sub.getFilePath()),
                hints.sheetName(), hints.headerRowIndex());
        if (extraction == null) {
            return ResponseEntity.status(502).body("pe-sub-extraction unreachable — alias saved, re-extraction pending.");
        }

        storeExtractionResult(id, extraction);
        return ResponseEntity.ok().build();
    }

    // ── GET /api/submissions ─────────────────────────────────────────────────

    @GetMapping
    public List<SubmissionDto> list(@RequestParam(required = false) Integer facilityId) {
        List<Submission> subs = (facilityId != null
            ? submissions.findByFacilityIdOrderByCreatedAtDesc(facilityId)
            : submissions.findAllByOrderByCreatedAtDesc())
            .stream().filter(s -> !"Processing".equals(s.getStatus())).toList();

        Set<Integer> ids = subs.stream().map(Submission::getFacilityId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, String> nameById = new HashMap<>();
        facilities.findAllById(new HashSet<>(ids)).forEach(f -> nameById.put(f.getId(), f.getName()));

        return subs.stream().map(s -> toDto(s, nameById.getOrDefault(s.getFacilityId(), "—"))).toList();
    }

    // ── GET /api/submissions/:id ─────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<SubmissionDto> get(@PathVariable int id) {
        return submissions.findById(id).map(s -> {
            String facilityName = s.getFacilityId() != null
                ? facilities.findById(s.getFacilityId()).map(Facility::getName).orElse("—")
                : "—";
            return ResponseEntity.ok(toDto(s, facilityName));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /api/submissions/:id/confirm ────────────────────────────────────

    record ConfirmResponse(boolean templateSaved, String agentBank) {}

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable int id, HttpServletRequest request) {
        Optional<Submission> subOpt = submissions.findById(id);
        if (subOpt.isEmpty()) return ResponseEntity.notFound().build();
        Submission sub = subOpt.get();

        String agentBank = sub.getAgentBank();
        boolean templateSaved = false;

        if (templateRepo.findByAgentBankIgnoreCase(agentBank).isEmpty()) {
            Optional<SubmissionExtraction> extOpt = extractionRepo.findBySubmissionId(id);
            if (extOpt.isPresent() && extOpt.get().getSheetName() != null) {
                SubmissionExtraction ext = extOpt.get();
                BbTemplate t = new BbTemplate();
                t.setAgentBank(agentBank);
                t.setSheetName(ext.getSheetName());
                t.setHeaderRowIndex(ext.getHeaderRowIndex());
                t.setAutoLearned(true);
                templateRepo.save(t);
                templateSaved = true;
            }
        }

        auditService.log("Extraction Confirmed", "Submission #" + id + " extraction confirmed",
            sub.getFacilityId(), "J. Smith", auditService.extractIp(request));
        return ResponseEntity.ok(new ConfirmResponse(templateSaved, agentBank));
    }

    // ── POST /api/submissions/:id/abort ──────────────────────────────────────

    @Transactional
    @PostMapping("/{id}/abort")
    public ResponseEntity<?> abort(@PathVariable int id, HttpServletRequest request) {
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission sub = opt.get();
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

        auditService.log("Abort", "Submission #" + id + " aborted", facilityId, "J. Smith", auditService.extractIp(request));

        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SubmissionDto toDto(Submission s, String facilityName) {
        return new SubmissionDto(
            s.getId(), s.getFacilityId(), facilityName,
            s.getAgentBank(), s.getPeriodMonth(), s.getStatus(),
            s.getFileName(), s.getUploadedBy(), s.getNotes(),
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
            toDecimalFieldFromStr(fields.get("AGENT_RATE")),
            toDecimalFieldFromStr(fields.get("CONCENTRATION_LIMIT")),
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

    private IngestRequest.DecimalField toDecimalFieldFromStr(ExtractionResponse.FieldValue f) {
        if (f == null) return null;
        return new IngestRequest.DecimalField(parseNumericSafe(f.value()), f.confidence(), f.sourceHeader());
    }

    private String fieldStr(Map<String, ExtractionResponse.FieldValue> fields, String key) {
        ExtractionResponse.FieldValue f = fields != null ? fields.get(key) : null;
        return (f != null && f.value() != null) ? f.value() : "";
    }

    private BigDecimal fieldDec(Map<String, ExtractionResponse.FieldValue> fields, String key) {
        ExtractionResponse.FieldValue f = fields != null ? fields.get(key) : null;
        return f != null ? parseNumericSafe(f.value()) : null;
    }

    private BigDecimal parseNumericSafe(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.trim().replaceAll("[,$%]", "").replaceAll("\\s+", "");
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
                return new BigDecimal(numeric).multiply(multiplier);
            }
            return new BigDecimal(cleaned);
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

    private String fmtMoney(BigDecimal v) {
        if (v == null) return "";
        BigDecimal abs = v.abs();
        if (abs.compareTo(new BigDecimal("1000000000")) >= 0)
            return String.format("$%.1fB", v.divide(new BigDecimal("1000000000"), 1, RoundingMode.HALF_UP));
        if (abs.compareTo(new BigDecimal("1000000")) >= 0)
            return String.format("$%.1fM", v.divide(new BigDecimal("1000000"), 1, RoundingMode.HALF_UP));
        if (abs.compareTo(BigDecimal.ZERO) == 0) return "";
        return String.format("$%,.0f", v);
    }

    private String fmtRate(BigDecimal v) {
        if (v == null) return "";
        BigDecimal pct = v.compareTo(BigDecimal.ONE) < 0
            ? v.multiply(BigDecimal.valueOf(100)) : v;
        return String.format("%.1f%%", pct);
    }

    private String confidenceNote(double confidence) {
        if (confidence >= 1.0)  return "Exact match";
        if (confidence >= 0.95) return "Matched via fuzzy similarity";
        return "Matched via alias dictionary";
    }

    private String friendlyFormat(String fmt) {
        if (fmt == null) return "Unknown template";
        return switch (fmt) {
            case "CITIBANK"           -> "Excel Workbook — Citibank template";
            case "JPM"                -> "Excel Workbook — JPMorgan Chase template";
            case "GOLDMAN_SACHS"      -> "Excel Workbook — Goldman Sachs Bank USA template";
            case "BARCLAYS"           -> "Excel Workbook — Barclays template";
            case "BANK_OF_AMERICA"    -> "Excel Workbook — Bank of America template";
            case "WELLS_FARGO"        -> "Excel Workbook — Wells Fargo template";
            case "CITIZENS_FINANCIAL" -> "Excel Workbook — Citizens Financial Group template";
            case "PNC_BANK"           -> "Excel Workbook — PNC Bank template";
            case "FIFTH_THIRD"        -> "Excel Workbook — Fifth Third Bank template";
            case "HUNTINGTON"         -> "Excel Workbook — Huntington National Bank template";
            case "WHITE_OAK"          -> "Excel Workbook — White Oak Global Advisors template";
            case "ARES"               -> "Excel Workbook — Ares Management template";
            case "MIDCAP_FINANCIAL"   -> "Excel Workbook — MidCap Financial template";
            case "INTERNAL"           -> "Excel Workbook — UBS Internal template";
            default                   -> "Excel Workbook — Unknown template";
        };
    }
}
