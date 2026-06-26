package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.BbTemplate;

import java.time.LocalDateTime;
import java.util.List;

public record BbTemplateDto(
    Integer                  id,
    String                   templateName,
    String                   templateClass,
    String                   sheetName,
    Integer                  headerRowIndex,
    boolean                  autoLearned,
    int                      trancheCount,
    boolean                  hasGroupingRows,
    boolean                  hasColorFlags,
    int                      summaryRowsAboveHeader,
    LocalDateTime            createdAt,
    LocalDateTime            updatedAt,
    List<BbTemplateTabDto>   tabs
) {
    public static BbTemplateDto from(BbTemplate t, List<BbTemplateTabDto> tabs) {
        return new BbTemplateDto(
            t.getId(),
            t.getTemplateName(),
            t.getTemplateClass(),
            t.getSheetName(),
            t.getHeaderRowIndex(),
            t.isAutoLearned(),
            t.getTranchCount(),
            t.isHasGroupingRows(),
            t.isHasColorFlags(),
            t.getSummaryRowsAboveHeader(),
            t.getCreatedAt(),
            t.getUpdatedAt(),
            tabs
        );
    }
}
