package com.ubs.pesubapi.dto;

import java.math.BigDecimal;
import java.util.List;

public record IngestRequest(
    Integer facilityId,
    ExtractionPayload extraction
) {
    public record ExtractionPayload(
        TemplateInfo template,
        List<ExtractedLpRow> records,
        int totalFlagged
    ) {}

    public record TemplateInfo(String format, String version, int headerRowIndex) {}

    public record ExtractedLpRow(
        int rowIndex,
        StringField investorName,
        DecimalField commitment,
        DecimalField uncalled,
        DecimalField aum,
        DecimalField agentRate,
        DecimalField concentrationLimit,
        StringField sp,
        StringField mdy,
        StringField fitch,
        StringField nav,
        StringField agentCls,
        StringField parent,
        boolean requiresReview,
        List<WarningEntry> warnings
    ) {}

    public record StringField(String value, double confidence, String sourceHeader) {}

    public record DecimalField(BigDecimal value, double confidence, String sourceHeader) {}

    public record WarningEntry(String field, String message, int rowIndex) {}
}
