package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.BbTemplateDto;
import com.ubs.pesubapi.dto.BbTemplateRequest;
import com.ubs.pesubapi.dto.BbTemplateRequest.BbTemplateGroupRequest;
import com.ubs.pesubapi.dto.BbTemplateRequest.BbTemplateTabRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

/**
 * Parses a structured Excel workbook into a BB template record.
 *
 * Expected workbook structure (see pe-sub-docs/WORKBOOK_*.md for per-template examples):
 *   Sheet "Template" — one header row (row 0) + one data row (row 1) with template-level fields.
 *   Sheet "Tabs"     — one header row + one data row per tab.
 *   Sheet "Groups"   — one header row + one data row per LP category group section.
 */
@Service
public class BbTemplateImportService {

    private static final List<String> DEFAULT_SKIP_KEYWORDS =
        List.of("Total", "Subtotal", "Sub-Total", "Grand Total", "Sum", "Net Total");

    private final BbTemplateService templateService;

    public BbTemplateImportService(BbTemplateService templateService) {
        this.templateService = templateService;
    }

    public BbTemplateDto importFromExcel(MultipartFile file) {
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            BbTemplateRequest req = parseWorkbook(wb);
            return templateService.create(req);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Failed to read uploaded Excel file: " + e.getMessage());
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private BbTemplateRequest parseWorkbook(Workbook wb) {
        Sheet templateSheet = requireSheet(wb, "Template");
        Sheet tabsSheet     = requireSheet(wb, "Tabs");
        Sheet groupsSheet   = wb.getSheet("Groups"); // optional (Class B/C may have no groups)

        Map<String, String> tmplRow = readSingleDataRow(templateSheet);
        List<Map<String, String>> tabRows   = readDataRows(tabsSheet);
        List<Map<String, String>> groupRows = groupsSheet != null ? readDataRows(groupsSheet) : List.of();

        // Build group requests indexed by tab_role so they can be attached to each tab
        Map<String, List<BbTemplateGroupRequest>> groupsByTabRole = new LinkedHashMap<>();
        for (Map<String, String> gRow : groupRows) {
            String role = required(gRow, "tab_role", "Groups sheet");
            int    sort = intVal(gRow, "group_sort", 1);
            String text = required(gRow, "header_text", "Groups sheet");
            String cls  = required(gRow, "classification", "Groups sheet");
            groupsByTabRole
                .computeIfAbsent(role, k -> new ArrayList<>())
                .add(new BbTemplateGroupRequest(sort, text, cls));
        }

        // Build tab requests
        List<BbTemplateTabRequest> tabs = new ArrayList<>();
        for (Map<String, String> tRow : tabRows) {
            String role      = required(tRow, "tab_role", "Tabs sheet");
            int    sort      = intVal(tRow, "tab_sort", 1);
            String sheet     = tRow.get("sheet_name");
            Integer hdrRow   = tRow.containsKey("header_row_index") ? intOrNull(tRow.get("header_row_index")) : null;
            int    hdrSpan   = intVal(tRow, "header_row_span", 1);
            List<String> skip = parseSkipKeywords(tRow.get("skip_row_keywords"));
            List<BbTemplateGroupRequest> groups = groupsByTabRole.getOrDefault(role, List.of());
            tabs.add(new BbTemplateTabRequest(role, sort, sheet, hdrRow, hdrSpan, skip, groups));
        }

        return new BbTemplateRequest(
            required(tmplRow, "agent_bank", "Template sheet"),
            required(tmplRow, "template_class", "Template sheet"),
            tmplRow.get("sheet_name"),
            intOrNull(tmplRow.get("header_row_index")),
            boolVal(tmplRow, "auto_learned", false),
            intVal(tmplRow, "tranche_count", 1),
            boolVal(tmplRow, "has_grouping_rows", false),
            boolVal(tmplRow, "has_color_flags", false),
            intVal(tmplRow, "summary_rows_above_header", 0),
            tabs
        );
    }

    // ── Sheet helpers ─────────────────────────────────────────────────────────

    private Sheet requireSheet(Workbook wb, String name) {
        Sheet s = wb.getSheet(name);
        if (s == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Import workbook is missing required sheet: \"" + name + "\"");
        return s;
    }

    /** Row 0 = headers, row 1 = single data row → Map<header, value>. */
    private Map<String, String> readSingleDataRow(Sheet sheet) {
        List<Map<String, String>> rows = readDataRows(sheet);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Sheet \"" + sheet.getSheetName() + "\" has no data row (expected at least one row below the header).");
        return rows.getFirst();
    }

    /** Row 0 = headers, rows 1..N = data → List of Map<header, value>. */
    private List<Map<String, String>> readDataRows(Sheet sheet) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return List.of();

        List<String> headers = new ArrayList<>();
        for (Cell c : headerRow) headers.add(cellString(c).toLowerCase().replace(' ', '_'));

        List<Map<String, String>> result = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> map = new LinkedHashMap<>();
            boolean hasData = false;
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = row.getCell(c);
                String val = cell != null ? cellString(cell).trim() : "";
                if (!val.isEmpty()) hasData = true;
                map.put(headers.get(c), val);
            }
            if (hasData) result.add(map);
        }
        return result;
    }

    private String cellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf(cell.getNumericCellValue()); }
                catch (Exception e) { yield cell.getStringCellValue(); }
            }
            default -> "";
        };
    }

    // ── Value helpers ─────────────────────────────────────────────────────────

    private String required(Map<String, String> row, String key, String context) {
        String v = row.get(key);
        if (v == null || v.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Missing required field \"" + key + "\" in " + context + ".");
        return v.trim();
    }

    private int intVal(Map<String, String> row, String key, int def) {
        String v = row.get(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private Integer intOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private boolean boolVal(Map<String, String> row, String key, boolean def) {
        String v = row.get(key);
        if (v == null || v.isBlank()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "yes".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    private List<String> parseSkipKeywords(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_SKIP_KEYWORDS;
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
