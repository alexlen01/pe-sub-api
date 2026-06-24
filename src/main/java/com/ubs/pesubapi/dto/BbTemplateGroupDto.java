package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.BbTemplateGroup;

public record BbTemplateGroupDto(
    Integer id,
    int     groupSort,
    String  headerText,
    String  classification
) {
    public static BbTemplateGroupDto from(BbTemplateGroup g) {
        return new BbTemplateGroupDto(g.getId(), g.getGroupSort(), g.getHeaderText(), g.getClassification());
    }
}
