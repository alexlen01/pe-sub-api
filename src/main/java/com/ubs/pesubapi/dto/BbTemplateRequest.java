package com.ubs.pesubapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record BbTemplateRequest(
    @NotBlank String agentBank,
    @NotBlank @Pattern(regexp = "A|B|C", message = "templateClass must be A, B, or C") String templateClass,
    String          sheetName,
    Integer         headerRowIndex,
    boolean         autoLearned,
    int             trancheCount,
    boolean         hasGroupingRows,
    boolean         hasColorFlags,
    int             summaryRowsAboveHeader,
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
        Integer           headerRowIndex,
        int               headerRowSpan,
        List<String>      skipRowKeywords,
        @NotNull @Valid List<BbTemplateGroupRequest> groups
    ) {}
}
