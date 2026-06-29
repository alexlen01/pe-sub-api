package com.ubs.pesubapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/**
 * Create/import payload for a BB template. The {@code templateSlug} (Template ID, e.g.
 * "gs-blue-owl") is the stable identity; on create it is auto-versioned ("gs-blue-owl-1", …)
 * if it already exists. The full registry (recognition + display) fields mirror the
 * bb_templates schema so a single import workbook fully defines a template.
 */
public record BbTemplateRequest(
    String          templateSlug,
    @NotBlank String templateName,
    String          agentName,
    @NotBlank @Pattern(regexp = "A|B|C", message = "templateClass must be A, B, or C") String templateClass,
    String          sheetName,
    Integer         headerRowIndex,
    boolean         autoLearned,
    int             trancheCount,
    boolean         hasGroupingRows,
    boolean         hasColorFlags,
    boolean         autoDiscoverTabs,
    int             summaryRowsAboveHeader,
    String          summaryRowRange,
    Integer         titleRow,
    String          titleText,
    List<String>    detectKeys,
    List<Map<String, String>> legend,
    List<String>    notes,
    @NotNull @Valid List<BbTemplateTabRequest> tabs
) {
    public record BbTemplateGroupRequest(
        int    groupSort,
        @NotBlank String headerText,
        @NotBlank String classification
    ) {}

    public record BbTemplateTabRequest(
        @NotBlank String  tabRole,
        int               tabSort,
        String            sheetName,
        String            sleeveName,
        Integer           headerRowIndex,
        int               headerRowSpan,
        List<String>      skipRowKeywords,
        List<String>      columns,
        @NotNull @Valid List<BbTemplateGroupRequest> groups
    ) {}
}
