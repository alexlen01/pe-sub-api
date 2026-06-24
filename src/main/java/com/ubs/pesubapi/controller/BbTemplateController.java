package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.BbTemplateDto;
import com.ubs.pesubapi.dto.BbTemplateRequest;
import com.ubs.pesubapi.service.BbTemplateImportService;
import com.ubs.pesubapi.service.BbTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/bb-templates")
public class BbTemplateController {

    private final BbTemplateService       templateService;
    private final BbTemplateImportService importService;

    public BbTemplateController(BbTemplateService templateService,
                                BbTemplateImportService importService) {
        this.templateService = templateService;
        this.importService   = importService;
    }

    /** List all registered Agent BB templates (with their tabs and group sections). */
    @GetMapping
    public List<BbTemplateDto> list() {
        return templateService.list();
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
        return templateService.create(req);
    }

    /** Replace an existing template's definition (tabs and groups are fully replaced). */
    @PutMapping("/{id}")
    public BbTemplateDto update(@PathVariable int id, @Valid @RequestBody BbTemplateRequest req) {
        return templateService.update(id, req);
    }

    /** Delete a template and all its tabs and group sections. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable int id) {
        templateService.delete(id);
    }

    /**
     * Import a template from a structured Excel workbook.
     * Expected sheets: "Template" (one data row), "Tabs", "Groups".
     * See pe-sub-docs/WORKBOOK_*.md for per-template examples of the required format.
     */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public BbTemplateDto importTemplate(@RequestParam("file") MultipartFile file) {
        return importService.importFromExcel(file);
    }
}
