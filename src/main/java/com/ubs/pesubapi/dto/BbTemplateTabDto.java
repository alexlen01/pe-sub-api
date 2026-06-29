package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.BbTemplateTab;

import java.util.List;

public record BbTemplateTabDto(
    Integer              id,
    String               tabRole,
    int                  tabSort,
    String               sheetName,
    String               sleeveName,
    Integer              headerRowIndex,
    int                  headerRowSpan,
    List<String>         skipRowKeywords,
    List<String>         columns,
    List<BbTemplateGroupDto> groups
) {
    public static BbTemplateTabDto from(BbTemplateTab t, List<BbTemplateGroupDto> groups) {
        return new BbTemplateTabDto(
            t.getId(),
            t.getTabRole().name(),
            t.getTabSort(),
            t.getSheetName(),
            t.getSleeveName(),
            t.getHeaderRowIndex(),
            t.getHeaderRowSpan(),
            t.getSkipRowKeywords(),
            t.getColumns(),
            groups
        );
    }
}
