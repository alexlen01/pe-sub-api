package com.ubs.pesubapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Raw structural signals returned by pe-sub-extraction's {@code POST /api/inspect}: per-sheet
 * names and the first rows of each sheet. pe-sub-api matches these against the bb_templates
 * registry to recognise the template before requesting extraction.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkbookSignals(String fileName, List<SheetSignals> sheets) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SheetSignals(String name, List<List<String>> rows) {}

    public List<SheetSignals> sheetsOrEmpty() {
        return sheets != null ? sheets : List.of();
    }
}
