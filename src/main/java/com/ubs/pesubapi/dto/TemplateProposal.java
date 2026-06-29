package com.ubs.pesubapi.dto;

import java.util.List;

/**
 * A deterministic, no-AI template proposal produced by {@code TemplateProfiler} from an uploaded
 * Agent BB workbook's structural signals. Structural facts (header row, columns, tab count) come
 * back high-confidence; semantic guesses (agent/fund name, group→LP-category mapping) are flagged
 * medium/low for one-click operator confirmation before saving to the registry.
 */
public record TemplateProposal(
    ProfiledField<String>  slug,
    ProfiledField<String>  agentName,
    ProfiledField<String>  titleText,
    ProfiledField<String>  workbook,          // "single" | "multiple"
    List<ProfiledTab>      tabs,
    ProfiledField<Boolean> autoDiscoverTabs,
    List<String>           detectKeys,
    String                 overallConfidence  // "high" | "medium" | "low"
) {
    public record ProfiledField<T>(T value, String confidence, String evidence) {
        public static <T> ProfiledField<T> of(T value, String confidence, String evidence) {
            return new ProfiledField<>(value, confidence, evidence);
        }
    }

    public record ProfiledTab(
        String                  sheetName,
        ProfiledField<Integer>  headerRowIndex,   // 0-based
        List<String>            columns,
        int                     matchedCanonical, // "# Hdrs that mapped to a canonical field"
        List<ProposedGroup>     groups
    ) {}

    public record ProposedGroup(String headerText, String classification, String confidence) {}
}
