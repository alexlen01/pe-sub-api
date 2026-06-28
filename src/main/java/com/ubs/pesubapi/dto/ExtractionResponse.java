package com.ubs.pesubapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionResponse(
    TemplateInfo template,
    List<ExtractedRecord> records,
    int totalFlagged,
    List<FieldMappingEntry> fieldMappings,
    List<String> unrecognizedColumns
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TemplateInfo(String format, String version, int headerRowIndex, String sheetName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedRecord(
        int rowIndex,
        Map<String, FieldValue> fields,
        boolean requiresReview,
        List<Warning> warnings,
        String fundSleeve
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldValue(String value, double confidence, String sourceHeader) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Warning(String field, String message, int rowIndex) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldMappingEntry(String extractedHeader, String canonicalField, double confidence) {}
}
