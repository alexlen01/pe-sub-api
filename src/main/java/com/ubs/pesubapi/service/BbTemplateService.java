package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.BbTemplateDto;
import com.ubs.pesubapi.dto.BbTemplateGroupDto;
import com.ubs.pesubapi.dto.BbTemplateRequest;
import com.ubs.pesubapi.dto.BbTemplateTabDto;
import com.ubs.pesubapi.entity.BbTemplate;
import com.ubs.pesubapi.entity.BbTemplateGroup;
import com.ubs.pesubapi.entity.BbTemplateTab;
import com.ubs.pesubapi.entity.BbTemplateTab.TabRole;
import com.ubs.pesubapi.repository.BbTemplateGroupRepository;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.BbTemplateTabRepository;
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

    public BbTemplateService(BbTemplateRepository templateRepo,
                             BbTemplateTabRepository tabRepo,
                             BbTemplateGroupRepository groupRepo) {
        this.templateRepo = templateRepo;
        this.tabRepo      = tabRepo;
        this.groupRepo    = groupRepo;
    }

    // ── Queries ────────────────────────────────────────────────────────────────

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

    @SuppressWarnings("null")
    @Transactional
    public BbTemplateDto create(BbTemplateRequest req) {
        BbTemplate entity = applyRequest(new BbTemplate(), req);
        entity = templateRepo.save(entity);
        saveTabs(entity, req.tabs());
        return toDto(entity);
    }

    @SuppressWarnings("null")
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

    @Transactional
    public void delete(int id) {
        if (!templateRepo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BB template not found: " + id);
        }
        // Cascade: tabs → groups deleted by FK ON DELETE CASCADE in Postgres
        templateRepo.deleteById(id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BbTemplate applyRequest(BbTemplate entity, BbTemplateRequest req) {
        entity.setTemplateName(req.templateName());
        entity.setTemplateClass(req.templateClass());
        entity.setSheetName(req.sheetName());
        entity.setHeaderRowIndex(req.headerRowIndex());
        entity.setAutoLearned(req.autoLearned());
        entity.setTrancheCount(req.trancheCount());
        entity.setHasGroupingRows(req.hasGroupingRows());
        entity.setHasColorFlags(req.hasColorFlags());
        entity.setSummaryRowsAboveHeader(req.summaryRowsAboveHeader());
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
            tab.setHeaderRowIndex(tabReq.headerRowIndex());
            tab.setHeaderRowSpan(tabReq.headerRowSpan() < 1 ? 1 : tabReq.headerRowSpan());
            if (tabReq.skipRowKeywords() != null && !tabReq.skipRowKeywords().isEmpty()) {
                tab.setSkipRowKeywords(tabReq.skipRowKeywords());
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
