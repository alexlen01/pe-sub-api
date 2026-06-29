package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.ResolvedTemplate;
import com.ubs.pesubapi.dto.WorkbookSignals;
import com.ubs.pesubapi.entity.BbTemplate;
import com.ubs.pesubapi.entity.BbTemplateGroup;
import com.ubs.pesubapi.entity.BbTemplateTab;
import com.ubs.pesubapi.entity.BbTemplateTab.TabRole;
import com.ubs.pesubapi.repository.BbTemplateGroupRepository;
import com.ubs.pesubapi.repository.BbTemplateRepository;
import com.ubs.pesubapi.repository.BbTemplateTabRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for "which Agent BB template is this workbook?".
 *
 * <p>Recognition was previously split and conflicting: an agent-bank keyword matcher and a set of
 * hardcoded structural fund profiles in pe-sub-extraction, plus the DB registry that only fed
 * classification indirectly. This service consolidates recognition in pe-sub-api, driven entirely
 * by the {@code bb_templates} registry, and emits a concrete {@link ResolvedTemplate} that the
 * extraction engine parses against deterministically.
 *
 * <p>Signals are scored against every registered template (highest score wins, ties broken by
 * signal strength); an operator-forced template always wins outright. The hierarchy:
 * <ol>
 *   <li>filename keyword ({@code detect_keys} in the uploaded file name) — most reliable</li>
 *   <li>title-anchor text ({@code title_text}) found in the sheet body — unique per fund</li>
 *   <li>{@code detect_keys} found anywhere in the sheet cells (fund name in a header block)</li>
 *   <li>exact named tab present (distinctive sleeve tabs)</li>
 *   <li>agent-bank name match — weak fallback</li>
 * </ol>
 */
@Service
public class TemplateRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(TemplateRecognitionService.class);

    // Minimum score before a match is reported; below this the engine auto-detects sheet/header.
    private static final int MIN_SCORE = 30;

    private static final int SCORE_FILENAME   = 100;
    private static final int SCORE_TITLE      = 50;
    private static final int SCORE_DETECT_KEY = 20;
    private static final int SCORE_NAMED_TAB  = 15;
    private static final int SCORE_AGENT_BANK = 10;

    private final BbTemplateRepository      templateRepo;
    private final BbTemplateTabRepository   tabRepo;
    private final BbTemplateGroupRepository groupRepo;

    public TemplateRecognitionService(BbTemplateRepository templateRepo,
                                      BbTemplateTabRepository tabRepo,
                                      BbTemplateGroupRepository groupRepo) {
        this.templateRepo = templateRepo;
        this.tabRepo      = tabRepo;
        this.groupRepo    = groupRepo;
    }

    /**
     * Recognises the template for a workbook. An operator-forced template name/slug wins outright;
     * otherwise the registry is scored against the workbook signals. Returns
     * {@link ResolvedTemplate#unknown()} when nothing clears {@link #MIN_SCORE}.
     */
    public ResolvedTemplate recognize(WorkbookSignals signals, String agentBank, String forcedTemplate) {
        List<BbTemplate> templates = templateRepo.findAll();
        if (templates.isEmpty()) {
            log.info("Recognition: registry is empty; UNKNOWN");
            return ResolvedTemplate.unknown();
        }

        if (forcedTemplate != null && !forcedTemplate.isBlank()) {
            String key = normalize(forcedTemplate);
            BbTemplate forced = templates.stream()
                .filter(t -> key.equals(normalize(t.getTemplateName())) || key.equals(normalize(t.getTemplateSlug())))
                .findFirst().orElse(null);
            if (forced != null) {
                log.info("Recognition: operator-forced template '{}'", forced.getTemplateName());
                return build(forced, "forced");
            }
            log.info("Recognition: forced template '{}' not in registry; falling back to auto-detection", forcedTemplate);
        }

        String fileKey = signals != null ? normalize(signals.fileName()) : "";
        List<String> cells = signals == null ? List.of() : signals.sheetsOrEmpty().stream()
            .flatMap(s -> s.rows() != null ? s.rows().stream() : List.<List<String>>of().stream())
            .flatMap(List::stream)
            .filter(Objects::nonNull)
            .map(this::normalize)
            .filter(c -> !c.isBlank())
            .toList();
        Set<String> sheetNames = signals == null ? Set.of() : signals.sheetsOrEmpty().stream()
            .map(s -> normalize(s.name()))
            .collect(Collectors.toSet());

        BbTemplate best = null;
        int bestScore = 0;
        String bestBy = "none";
        for (BbTemplate t : templates) {
            int score = 0;
            String by = "";

            String filenameHit = firstContained(fileKey, t.getDetectKeys());
            if (filenameHit != null) {
                score += SCORE_FILENAME;
                by = "filename:" + filenameHit;
            } else {
                // Slug tokens in the filename (e.g. "kkr-ascendant" ⊂ "Agent-BB-KKR-Ascendant-Fund"),
                // so a template with no detect_keys is still recognised from a conventionally-named file.
                String slugNorm = normalize(t.getTemplateSlug());
                if (!slugNorm.isBlank() && fileKey.contains(slugNorm)) {
                    score += SCORE_FILENAME;
                    by = "filenameSlug:" + t.getTemplateSlug();
                }
            }

            if (t.getTitleText() != null && !t.getTitleText().isBlank()) {
                String title = normalize(t.getTitleText());
                if (!title.isBlank() && cells.stream().anyMatch(c -> c.contains(title))) {
                    score += SCORE_TITLE;
                    if (by.isEmpty()) by = "title";
                }
            }

            String cellKeyHit = firstContainedInAny(cells, t.getDetectKeys());
            if (cellKeyHit != null) { score += SCORE_DETECT_KEY; if (by.isEmpty()) by = "detectKey:" + cellKeyHit; }

            List<BbTemplateTab> tabs = tabRepo.findByTemplateIdAndTabRoleOrderByTabSortAsc(t.getId(), TabRole.LP_GRID);
            boolean namedTab = tabs.stream()
                .map(BbTemplateTab::getSheetName)
                .filter(Objects::nonNull)
                .anyMatch(sn -> sheetNames.contains(normalize(sn)));
            if (namedTab) { score += SCORE_NAMED_TAB; if (by.isEmpty()) by = "namedTab"; }

            if (agentBank != null && t.getAgentName() != null
                    && normalize(agentBank).contains(normalize(t.getAgentName()))) {
                score += SCORE_AGENT_BANK;
                if (by.isEmpty()) by = "agentBank";
            }

            if (score > bestScore) { bestScore = score; best = t; bestBy = by; }
        }

        if (best == null || bestScore < MIN_SCORE) {
            log.info("Recognition: no confident match (bestScore={}, threshold={}); UNKNOWN", bestScore, MIN_SCORE);
            return ResolvedTemplate.unknown();
        }
        log.info("Recognition: matched template='{}' score={} matchedBy='{}'", best.getTemplateName(), bestScore, bestBy);
        return build(best, bestBy);
    }

    /** Builds the concrete parse definition for a matched template from its LP_GRID tab(s) + groups. */
    private ResolvedTemplate build(BbTemplate t, String matchedBy) {
        List<BbTemplateTab> tabs = tabRepo.findByTemplateIdAndTabRoleOrderByTabSortAsc(t.getId(), TabRole.LP_GRID);
        BbTemplateTab first = tabs.isEmpty() ? null : tabs.getFirst();

        Integer header = first != null && first.getHeaderRowIndex() != null
            ? first.getHeaderRowIndex() : t.getHeaderRowIndex();
        Integer span = first != null ? first.getHeaderRowSpan() : null;
        List<String> skip = tabs.stream()
            .flatMap(tab -> tab.getSkipRowKeywords().stream())
            .distinct().toList();
        List<String> columns = first != null ? first.getColumns() : List.of();

        Map<String, String> groupMap = new LinkedHashMap<>();
        if (first != null) {
            for (BbTemplateGroup g : groupRepo.findByTabIdOrderByGroupSortAsc(first.getId())) {
                groupMap.put(g.getHeaderText(), g.getClassification());
            }
        }

        if (tabs.size() > 1) {
            List<String> sheetNames = tabs.stream()
                .map(BbTemplateTab::getSheetName).filter(Objects::nonNull).toList();
            return new ResolvedTemplate(true, t.getTemplateSlug(), t.getTemplateName(), t.getAgentName(),
                sheetNames.isEmpty() ? null : sheetNames.getFirst(), header, span, sheetNames,
                false, groupMap, skip, columns, matchedBy);
        }
        if (t.isAutoDiscoverTabs()) {
            return new ResolvedTemplate(true, t.getTemplateSlug(), t.getTemplateName(), t.getAgentName(),
                null, header, span, List.of(), true, groupMap, skip, columns, matchedBy);
        }
        String sheet = first != null && first.getSheetName() != null ? first.getSheetName() : t.getSheetName();
        return new ResolvedTemplate(true, t.getTemplateSlug(), t.getTemplateName(), t.getAgentName(),
            sheet, header, span, List.of(), false, groupMap, skip, columns, matchedBy);
    }

    /** Returns the first key whose normalised form is a substring of {@code haystack}, else null. */
    private String firstContained(String haystack, List<String> keys) {
        if (haystack == null || haystack.isBlank() || keys == null) return null;
        for (String k : keys) {
            String nk = normalize(k);
            if (!nk.isBlank() && haystack.contains(nk)) return k;
        }
        return null;
    }

    /** Returns the first key found as a substring of any cell, else null. */
    private String firstContainedInAny(List<String> cells, List<String> keys) {
        if (keys == null) return null;
        for (String k : keys) {
            String nk = normalize(k);
            if (nk.isBlank()) continue;
            if (cells.stream().anyMatch(c -> c.contains(nk))) return k;
        }
        return null;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
