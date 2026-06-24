package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

/**
 * Builds the classification configuration that pe-sub-api passes to pe-sub-extraction.
 *
 * Agent BB templates frequently group LPs into Agent LP Classification sections using
 * group-header rows (e.g. "Designated PWM") rather than a per-row column. The
 * bb_template_groups table holds, per template tab, the header text and the canonical
 * Agent LP Classification it maps to. This service serialises those rows for the
 * agent bank of the current submission into a JSON map of shape
 * {@code { "Designated PWM Investors": "Designated PWM", ... }} so the extraction
 * engine can recognise the section rows and fill the classification down.
 *
 * Returns null when no template / group config exists for the bank — the extraction
 * service then falls back to recognising the standard Agent LP Classification values
 * by their canonical names.
 */
@Service
public class ClassificationConfigBuilder {

    private static final Logger log = LoggerFactory.getLogger(ClassificationConfigBuilder.class);

    private final BbTemplateRepository      templateRepo;
    private final BbTemplateTabRepository   tabRepo;
    private final BbTemplateGroupRepository groupRepo;
    private final ObjectMapper              mapper;

    public ClassificationConfigBuilder(BbTemplateRepository templateRepo,
                                       BbTemplateTabRepository tabRepo,
                                       BbTemplateGroupRepository groupRepo,
                                       ObjectMapper mapper) {
        this.templateRepo = templateRepo;
        this.tabRepo      = tabRepo;
        this.groupRepo    = groupRepo;
        this.mapper       = mapper;
    }

    /**
     * Returns a JSON string of {@code { headerText: classification }} for the agent bank's
     * LP_GRID tab group headers, or null when none are configured.
     */
    public String buildJson(String agentBank) {
        return buildJson(agentBank, null);
    }

    /**
     * Returns group-header config for the selected agent bank. If the facility agent bank does
     * not directly identify a seeded grouping template, falls back to the operator-forced fund
     * template name (e.g. "Petershill IV" -> "Petershill IV / Wells Fargo").
     */
    public String buildJson(String agentBank, String forceTemplate) {
        // When an agent uses multiple template classes (e.g. Wells Fargo Class A and B),
        // group-header classification only applies to the template that has grouping rows.
        Optional<BbTemplate> template = findGroupingTemplate(agentBank, forceTemplate);
        if (template.isEmpty()) return null;

        Optional<BbTemplateTab> lpGrid =
            tabRepo.findByTemplateIdAndTabRole(template.get().getId(), TabRole.LP_GRID);
        if (lpGrid.isEmpty()) return null;

        List<BbTemplateGroup> groups = groupRepo.findByTabIdOrderByGroupSortAsc(lpGrid.get().getId());
        if (groups.isEmpty()) return null;

        Map<String, String> config = new LinkedHashMap<>();
        for (BbTemplateGroup g : groups) {
            config.put(g.getHeaderText(), g.getClassification());
        }

        try {
            return mapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise classification config for {} — extraction will use standard values: {}",
                agentBank, e.getMessage());
            return null;
        }
    }

    private Optional<BbTemplate> findGroupingTemplate(String agentBank, String forceTemplate) {
        String forcedKey = normalize(forceTemplate);
        if (forcedKey != null) {
            Optional<BbTemplate> forced = templateRepo.findAll().stream()
                .filter(BbTemplate::isHasGroupingRows)
                .filter(t -> matchesForcedTemplate(t.getAgentBank(), forcedKey))
                .findFirst();
            if (forced.isPresent()) return forced;
        }

        if (agentBank != null && !agentBank.isBlank()) {
            Optional<BbTemplate> byAgent = templateRepo.findAllByAgentBankIgnoreCase(agentBank)
                .stream()
                .filter(BbTemplate::isHasGroupingRows)
                .findFirst();
            if (byAgent.isPresent()) return byAgent;
        }

        return Optional.empty();
    }

    private boolean matchesForcedTemplate(String agentBank, String forcedKey) {
        String templateKey = normalize(agentBank);
        return templateKey != null
            && (templateKey.equals(forcedKey) || templateKey.startsWith(forcedKey + " "));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
