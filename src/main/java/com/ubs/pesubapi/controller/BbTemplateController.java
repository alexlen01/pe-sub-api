package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.BbTemplateDto;
import com.ubs.pesubapi.dto.BbTemplateRequest;
import com.ubs.pesubapi.dto.TemplateProposal;
import com.ubs.pesubapi.dto.WorkbookSignals;
import com.ubs.pesubapi.service.BbTemplateImportService;
import com.ubs.pesubapi.service.BbTemplateService;
import com.ubs.pesubapi.service.ExtractionClientService;
import com.ubs.pesubapi.service.TemplateProfiler;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/bb-templates")
public class BbTemplateController {

    private static final Logger log = LoggerFactory.getLogger(BbTemplateController.class);

    private final BbTemplateService       templateService;
    private final BbTemplateImportService importService;
    private final ExtractionClientService extractionClient;
    private final TemplateProfiler        profiler;

    public BbTemplateController(BbTemplateService templateService,
                                BbTemplateImportService importService,
                                ExtractionClientService extractionClient,
                                TemplateProfiler profiler) {
        this.templateService  = templateService;
        this.importService    = importService;
        this.extractionClient = extractionClient;
        this.profiler         = profiler;
    }

    /** List all registered Agent BB templates (with their tabs and group sections). */
    @GetMapping
    public List<BbTemplateDto> list() {
        List<BbTemplateDto> templates = templateService.list();
        log.info("BB template registry listed count={}", templates.size());
        return templates;
    }

    /** Get a single template by id. */
    @GetMapping("/{id}")
    public BbTemplateDto get(@PathVariable int id) {
        return templateService.findById(id);
    }

    /** Register a new template from a JSON request body. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BbTemplateDto create(@Valid @RequestBody BbTemplateRequest req) {
        BbTemplateDto created = templateService.create(req);
        log.info("BB template created id={} slug='{}' name='{}' class={} tabs={}",
            created.id(), created.templateSlug(), created.templateName(), created.templateClass(),
            created.tabs() != null ? created.tabs().size() : 0);
        return created;
    }

    /** Replace an existing template's definition (tabs and groups are fully replaced). */
    @PutMapping("/{id}")
    public BbTemplateDto update(@PathVariable int id, @Valid @RequestBody BbTemplateRequest req) {
        BbTemplateDto updated = templateService.update(id, req);
        log.info("BB template updated id={} slug='{}' name='{}' class={} tabs={}",
            id, updated.templateSlug(), updated.templateName(), updated.templateClass(),
            updated.tabs() != null ? updated.tabs().size() : 0);
        return updated;
    }

    /** Delete a template and all its tabs and group sections. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable int id) {
        templateService.delete(id);
        log.info("BB template deleted id={}", id);
    }

    /**
     * Import a template from a structured Excel workbook.
     * Expected sheets: "Template" (one data row), "Tabs", "Groups".
     * See pe-sub-docs/WORKBOOK_*.md for per-template examples of the required format.
     */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public BbTemplateDto importTemplate(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "mode", defaultValue = "create") String mode) {
        boolean upsert = "upsert".equalsIgnoreCase(mode);
        BbTemplateDto imported = importService.importFromExcel(file, upsert);
        log.info("BB template imported file='{}' mode={} id={} slug='{}' class={} tabs={}",
            file.getOriginalFilename(), upsert ? "upsert" : "create",
            imported.id(), imported.templateSlug(), imported.templateClass(),
            imported.tabs() != null ? imported.tabs().size() : 0);
        return imported;
    }

    /** Download the original structured workbook persisted when this template was imported. */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable int id) {
        BbTemplateService.SourceFile file = templateService.getSourceFile(id);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(file.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.parseMediaType(file.contentType()))
            .contentLength(file.content().length)
            .body(file.content());
    }

    /**
     * Self-adoption: profile an uploaded Agent BB workbook (deterministic, no AI) into a proposed
     * template — header row, columns, tab layout, agent/title clues, and LP-category groups — so
     * the operator can review and save it. Persists nothing.
     */
    @PostMapping(value = "/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TemplateProposal profile(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "agentBank", required = false) String agentBank) {
        WorkbookSignals signals = extractionClient.inspect(file, 200);
        if (signals == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "pe-sub-extraction unreachable — cannot profile workbook.");
        }
        TemplateProposal proposal = profiler.profile(signals, agentBank);
        log.info("BB template profiled file='{}' slug='{}' grids={} overall={}",
            file.getOriginalFilename(), proposal.slug().value(), proposal.tabs().size(), proposal.overallConfidence());
        return proposal;
    }
}
