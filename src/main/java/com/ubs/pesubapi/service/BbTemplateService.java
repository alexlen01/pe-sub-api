package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.BbTemplateDto;
import com.ubs.pesubapi.dto.BbTemplateGroupDto;
import com.ubs.pesubapi.dto.BbTemplateRequest;
import com.ubs.pesubapi.dto.BbTemplateTabDto;
import com.ubs.pesubapi.entity.BbTemplate;
import com.ubs.pesubapi.entity.BbTemplateFile;
import com.ubs.pesubapi.entity.BbTemplateGroup;
import com.ubs.pesubapi.entity.BbTemplateTab;
import com.ubs.pesubapi.entity.BbTemplateTab.TabRole;
import com.ubs.pesubapi.repository.BbTemplateGroupRepository;
import com.ubs.pesubapi.repository.BbTemplateFileRepository;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.BbTemplateTabRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class BbTemplateService {

    private final BbTemplateRepository      templateRepo;
    private final BbTemplateTabRepository   tabRepo;
    private final BbTemplateGroupRepository groupRepo;
    private final BbTemplateFileRepository  fileRepo;

    public BbTemplateService(BbTemplateRepository templateRepo,
                             BbTemplateTabRepository tabRepo,
                             BbTemplateGroupRepository groupRepo,
                             BbTemplateFileRepository fileRepo) {
        this.templateRepo = templateRepo;
        this.tabRepo      = tabRepo;
        this.groupRepo    = groupRepo;
        this.fileRepo     = fileRepo;
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    @Cacheable("bb-templates")
    @Transactional(readOnly = true)
    public List<BbTemplateDto> list() {
        return templateRepo.findAll().stream()
            .map(this::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public BbTemplateDto findById(int id) {
        return templateRepo.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "BB template not found: " + id));
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    @CacheEvict(value = "bb-templates", allEntries = true)
    @Transactional
    public BbTemplateDto create(BbTemplateRequest req) {
        BbTemplate entity = applyRequest(new BbTemplate(), req);
        // Template ID (slug) uniqueness with auto-versioning: gs-blue-owl, gs-blue-owl-1, …
        // When the import convention uses the slug as the display name, keep them in sync.
        String slug = req.templateSlug();
        if (slug != null && !slug.isBlank()) {
            String free = nextAvailableSlug(slug);
            entity.setTemplateSlug(free);
            if (!free.equals(slug) && slug.equals(req.templateName())) {
                entity.setTemplateName(free);
            }
        }
        entity = templateRepo.save(entity);
        saveTabs(entity, req.tabs());
        return toDto(entity);
    }

    @CacheEvict(value = "bb-templates", allEntries = true)
    @Transactional
    public BbTemplateDto upsertBySlug(BbTemplateRequest req) {
        String slug = req.templateSlug();
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "BB template import requires template_slug for upsert mode.");
        }
        return templateRepo.findByTemplateSlug(slug)
            .map(existing -> update(existing.getId(), req))
            .orElseGet(() -> create(req));
    }

    /** Returns {@code base} if free, else the first available {@code base-1}, {@code base-2}, … */
    private String nextAvailableSlug(String base) {
        if (templateRepo.findByTemplateSlug(base).isEmpty()) return base;
        for (int n = 1; ; n++) {
            String candidate = base + "-" + n;
            if (templateRepo.findByTemplateSlug(candidate).isEmpty()) return candidate;
        }
    }

    @CacheEvict(value = "bb-templates", allEntries = true)
    @Transactional
    public BbTemplateDto update(int id, BbTemplateRequest req) {
        BbTemplate entity = templateRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "BB template not found: " + id));
        applyRequest(entity, req);
        entity = templateRepo.save(entity);
        List<BbTemplateTab> existingTabs = tabRepo.findByTemplateIdOrderByTabSortAsc(id);
        if (existingTabs != null && !existingTabs.isEmpty()) {
            tabRepo.deleteAll(existingTabs);
        }
        // Flush deletes to DB before inserts: prevents uq_template_tab_role violation
        // when the new LP_GRID tab would conflict with the just-removed one.
        // Groups are covered by ON DELETE CASCADE on bb_template_tabs.
        tabRepo.flush();
        saveTabs(entity, req.tabs());
        return toDto(entity);
    }

    @CacheEvict(value = "bb-templates", allEntries = true)
    @Transactional
    public void delete(int id) {
        if (!templateRepo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BB template not found: " + id);
        }
        // Cascade: tabs → groups deleted by FK ON DELETE CASCADE in Postgres
        templateRepo.deleteById(id);
    }

    @CacheEvict(value = "bb-templates", allEntries = true)
    @Transactional
    public BbTemplateDto storeSourceFile(int id, String fileName, String contentType, byte[] content) {
        BbTemplate template = templateRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "BB template not found: " + id));

        BbTemplateFile file = fileRepo.findById(id).orElseGet(BbTemplateFile::new);
        file.setTemplateId(id);
        file.setContentType(contentType);
        file.setContent(content);
        fileRepo.save(file);

        template.setSourceFileName(fileName);
        template.setSourceFileSize((long) content.length);
        templateRepo.save(template);
        return toDto(template);
    }

    @Transactional(readOnly = true)
    public SourceFile getSourceFile(int id) {
        BbTemplate template = templateRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "BB template not found: " + id));
        BbTemplateFile file = fileRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No imported workbook is stored for BB template: " + id));
        return new SourceFile(template.getSourceFileName(), file.getContentType(), file.getContent());
    }

    public record SourceFile(String fileName, String contentType, byte[] content) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BbTemplate applyRequest(BbTemplate entity, BbTemplateRequest req) {
        entity.setTemplateSlug(req.templateSlug());
        entity.setTemplateName(req.templateName());
        entity.setAgentName(req.agentName());
        entity.setTemplateClass(req.templateClass());
        entity.setSheetName(req.sheetName());
        entity.setHeaderRowIndex(req.headerRowIndex());
        entity.setAutoLearned(req.autoLearned());
        entity.setTrancheCount(req.trancheCount());
        entity.setHasGroupingRows(req.hasGroupingRows());
        entity.setHasColorFlags(req.hasColorFlags());
        entity.setAutoDiscoverTabs(req.autoDiscoverTabs());
        entity.setSummaryRowsAboveHeader(req.summaryRowsAboveHeader());
        entity.setSummaryRowRange(req.summaryRowRange());
        entity.setTitleRow(req.titleRow());
        entity.setTitleText(req.titleText());
        if (req.detectKeys() != null) entity.setDetectKeys(req.detectKeys());
        if (req.legend() != null) entity.setLegend(req.legend());
        if (req.notes() != null) entity.setNotes(req.notes());
        return entity;
    }

    private void saveTabs(BbTemplate template, List<BbTemplateRequest.BbTemplateTabRequest> tabReqs) {
        if (tabReqs == null) return;
        for (BbTemplateRequest.BbTemplateTabRequest tabReq : tabReqs) {
            BbTemplateTab tab = new BbTemplateTab();
            tab.setTemplate(template);
            tab.setTabRole(TabRole.valueOf(tabReq.tabRole()));
            tab.setTabSort(tabReq.tabSort());
            tab.setSheetName(tabReq.sheetName());
            tab.setSleeveName(tabReq.sleeveName());
            tab.setHeaderRowIndex(tabReq.headerRowIndex());
            tab.setHeaderRowSpan(tabReq.headerRowSpan() < 1 ? 1 : tabReq.headerRowSpan());
            if (tabReq.skipRowKeywords() != null) {
                tab.setSkipRowKeywords(tabReq.skipRowKeywords());
            }
            if (tabReq.columns() != null) {
                tab.setColumns(tabReq.columns());
            }
            tabRepo.save(tab);
            saveGroups(tab, tabReq.groups());
        }
    }

    private void saveGroups(BbTemplateTab tab, List<BbTemplateRequest.BbTemplateGroupRequest> groupReqs) {
        if (groupReqs == null) return;
        for (BbTemplateRequest.BbTemplateGroupRequest gReq : groupReqs) {
            BbTemplateGroup group = new BbTemplateGroup();
            group.setTab(tab);
            group.setGroupSort(gReq.groupSort());
            group.setHeaderText(gReq.headerText());
            group.setClassification(gReq.classification());
            groupRepo.save(group);
        }
    }

    BbTemplateDto toDto(BbTemplate t) {
        List<BbTemplateTabDto> tabDtos = tabRepo.findByTemplateIdOrderByTabSortAsc(t.getId())
            .stream()
            .map(tab -> {
                List<BbTemplateGroupDto> groups = groupRepo
                    .findByTabIdOrderByGroupSortAsc(tab.getId())
                    .stream()
                    .map(BbTemplateGroupDto::from)
                    .toList();
                return BbTemplateTabDto.from(tab, groups);
            })
            .toList();
        return BbTemplateDto.from(t, tabDtos);
    }
}
