package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.BbTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record BbTemplateDto(
    Integer                  id,
    String                   templateSlug,
    String                   templateName,
    String                   agentName,
    String                   templateClass,
    String                   sheetName,
    Integer                  headerRowIndex,
    boolean                  autoLearned,
    int                      trancheCount,
    boolean                  hasGroupingRows,
    boolean                  hasColorFlags,
    boolean                  autoDiscoverTabs,
    int                      summaryRowsAboveHeader,
    String                   summaryRowRange,
    Integer                  titleRow,
    String                   titleText,
    List<String>             detectKeys,
    List<Map<String, String>> legend,
    List<String>             notes,
    String                   sourceFileName,
    Long                     sourceFileSize,
    LocalDateTime            createdAt,
    LocalDateTime            updatedAt,
    List<BbTemplateTabDto>   tabs
) {
    public static BbTemplateDto from(BbTemplate t, List<BbTemplateTabDto> tabs) {
        return new BbTemplateDto(
            t.getId(),
            t.getTemplateSlug(),
            t.getTemplateName(),
            t.getAgentName(),
            t.getTemplateClass(),
            t.getSheetName(),
            t.getHeaderRowIndex(),
            t.isAutoLearned(),
            t.getTranchCount(),
            t.isHasGroupingRows(),
            t.isHasColorFlags(),
            t.isAutoDiscoverTabs(),
            t.getSummaryRowsAboveHeader(),
            t.getSummaryRowRange(),
            t.getTitleRow(),
            t.getTitleText(),
            t.getDetectKeys(),
            t.getLegend(),
            t.getNotes(),
            t.getSourceFileName(),
            t.getSourceFileSize(),
            t.getCreatedAt(),
            t.getUpdatedAt(),
            tabs
        );
    }
}
