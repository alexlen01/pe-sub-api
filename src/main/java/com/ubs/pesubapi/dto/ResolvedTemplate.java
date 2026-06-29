package com.ubs.pesubapi.dto;

import java.util.List;
import java.util.Map;

/**
 * The outcome of template recognition: a concrete, deterministic parse definition that pe-sub-api
 * pushes to pe-sub-extraction. The engine performs no recognition itself — it parses exactly per
 * this definition (sheet(s), header row + span, LP-category group map, skip keywords).
 *
 * @param recognized      whether a registry template matched (false → engine auto-detects sheet/header)
 * @param slug            stable template id (e.g. "gs-blue-owl")
 * @param templateName    fund template name (e.g. "Blue Owl GP Stakes V") — stored as the version
 * @param agentName       agent bank name
 * @param sheetName       primary sheet name (single-tab), or first sleeve (multi-tab)
 * @param headerRowIndex  0-based header row index
 * @param headerRowSpan   physical rows the column header occupies (stacked headers > 1)
 * @param sheetNames      ordered named sleeve tabs (multi-tab), else empty
 * @param autoDiscoverTabs scan all sheets (CCP-style) when sheet names vary per workbook
 * @param groupMap        LP-category group-header text → canonical Agent LP Classification
 * @param skipRowKeywords first-cell keywords whose rows are dropped before LP parsing
 * @param columns         ordered expected column headers (recognition fingerprint / display)
 * @param matchedBy       which recognition signal won (for logging/audit)
 */
public record ResolvedTemplate(
    boolean recognized,
    String slug,
    String templateName,
    String agentName,
    String sheetName,
    Integer headerRowIndex,
    Integer headerRowSpan,
    List<String> sheetNames,
    boolean autoDiscoverTabs,
    Map<String, String> groupMap,
    List<String> skipRowKeywords,
    List<String> columns,
    String matchedBy
) {
    public static ResolvedTemplate unknown() {
        return new ResolvedTemplate(false, null, null, null, null, null, null,
            List.of(), false, Map.of(), List.of(), List.of(), "none");
    }

    public boolean multiTab() {
        return sheetNames != null && sheetNames.size() > 1;
    }
}
