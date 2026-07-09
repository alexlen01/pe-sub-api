package com.ubs.pesubapi.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.dto.BbTemplateDto;
import com.ubs.pesubapi.dto.BbTemplateRequest;
import com.ubs.pesubapi.dto.BbTemplateRequest.BbTemplateGroupRequest;
import com.ubs.pesubapi.dto.BbTemplateRequest.BbTemplateTabRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

/**
 * Parses a structured "BB-Template-Import" Excel workbook into a {@link BbTemplateRequest}.
 *
 * <p>This is the canonical way the BB template registry is populated — the same path used by
 * "Upload Template" in the app and by integration tests. A template is identified by its
 * {@code template_slug} (Template ID); {@link BbTemplateService#create} auto-versions the slug
 * when it already exists.
 *
 * <p>Expected sheets (see {@code pe-sub-platform/public/BB-Template-Import-*.xlsx}):
 * <ul>
 *   <li>{@code Template} — one data row: template_slug, agent_bank, template_class, sheet_name,
 *       header_row_index (1-based Excel row), header_row_span, auto_learned, tranche_count,
 *       has_grouping_rows, has_color_flags, auto_discover_tabs, summary_rows_above_header,
 *       summary_row_range, detect_keys (comma-separated)</li>
 *   <li>{@code Tabs} — one row per tab: tab_role, tab_sort, sheet_name, sleeve_name,
 *       header_row_index (1-based), header_row_span, skip_row_keywords (CSV),
 *       expected_source_headers_json (JSON array of column headers)</li>
 *   <li>{@code Groups} — tab_role, group_sort, header_text, classification</li>
 *   <li>{@code Columns} — tab_role, column_sort, source_header (fallback for tab columns)</li>
 *   <li>{@code Legend} — style, meaning</li>
 *   <li>{@code Notes} — note_sort, note</li>
 * </ul>
 *
 * <p>The Template sheet carries no display name — the {@code template_slug} is used as the
 * {@code template_name} (per product decision: identity and display are the Template ID).
 * Header-row indices in the workbook are 1-based Excel rows and are converted to 0-based here.
 */
@Service
public class BbTemplateImportService {

    private static final Logger log = LoggerFactory.getLogger(BbTemplateImportService.class);

    private static final List<String> DEFAULT_SKIP_KEYWORDS =
        List.of("Total", "Subtotal", "Sub-Total", "Grand Total", "Sum", "Net Total");

    private final BbTemplateService templateService;
    private final ObjectMapper      mapper;

    public BbTemplateImportService(BbTemplateService templateService, ObjectMapper mapper) {
        this.templateService = templateService;
        this.mapper          = mapper;
    }

    public BbTemplateDto importFromExcel(MultipartFile file) {
        return importFromExcel(file, false);
    }

    public BbTemplateDto importFromExcel(MultipartFile file, boolean upsert) {
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            BbTemplateRequest req = parseWorkbook(wb);
            log.info("BB template import parsed file='{}' slug='{}' agent='{}' class={} sheet='{}' headerRow={} autoDiscover={} tabs={} notes={}",
                fileName(file), req.templateSlug(), req.agentName(), req.templateClass(),
                req.sheetName(), req.headerRowIndex(), req.autoDiscoverTabs(),
                req.tabs() != null ? req.tabs().size() : 0,
                req.notes() != null ? req.notes().size() : 0);
            return upsert ? templateService.upsertBySlug(req) : templateService.create(req);
        } catch (ResponseStatusException e) {
            log.warn("BB template import rejected file='{}': {}", fileName(file), e.getReason());
            throw e;
        } catch (IOException e) {
            log.warn("BB template import failed to read file='{}': {}", fileName(file), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Failed to read uploaded Excel file: " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("BB template import failed to parse file='{}': {}", fileName(file), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Failed to parse uploaded Excel template: " + e.getMessage());
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private BbTemplateRequest parseWorkbook(Workbook wb) {
        Sheet templateSheet = requireSheet(wb, "Template");
        Sheet tabsSheet     = requireSheet(wb, "Tabs");

        Map<String, String> tmpl = readSingleDataRow(templateSheet);
        List<Map<String, String>> tabRows    = readDataRows(tabsSheet);
        List<Map<String, String>> groupRows  = readOptionalRows(wb, "Groups");
        List<Map<String, String>> columnRows = readOptionalRows(wb, "Columns");
        List<Map<String, String>> legendRows = readOptionalRows(wb, "Legend");
        List<Map<String, String>> noteRows   = readOptionalRows(wb, "Notes");

        // Groups indexed by tab_role so they attach to the right tab.
        Map<String, List<BbTemplateGroupRequest>> groupsByRole = new LinkedHashMap<>();
        for (Map<String, String> g : groupRows) {
            String role = required(g, "tab_role", "Groups sheet");
            groupsByRole.computeIfAbsent(role, k -> new ArrayList<>())
                .add(new BbTemplateGroupRequest(
                    intVal(g, "group_sort", 1),
                    required(g, "header_text", "Groups sheet"),
                    required(g, "classification", "Groups sheet")));
        }

        // Columns (fallback for tab columns when a tab has no expected_source_headers_json),
        // ordered by column_sort, indexed by tab_role.
        Map<String, List<String>> columnsByRole = new LinkedHashMap<>();
        for (Map<String, String> c : columnRows) {
            String role = required(c, "tab_role", "Columns sheet");
            columnsByRole.computeIfAbsent(role, k -> new ArrayList<>())
                .add(required(c, "source_header", "Columns sheet"));
        }

        List<BbTemplateTabRequest> tabs = new ArrayList<>();
        for (Map<String, String> t : tabRows) {
            String role = required(t, "tab_role", "Tabs sheet");
            List<String> columns = parseJsonArray(t.get("expected_source_headers_json"));
            if (columns.isEmpty()) columns = columnsByRole.getOrDefault(role, List.of());
            tabs.add(new BbTemplateTabRequest(
                role,
                intVal(t, "tab_sort", 1),
                t.get("sheet_name"),
                t.get("sleeve_name"),
                toZeroBased(intOrNull(t.get("header_row_index"))),
                intVal(t, "header_row_span", 1),
                parseCsv(t.get("skip_row_keywords"), DEFAULT_SKIP_KEYWORDS),
                columns,
                groupsByRole.getOrDefault(role, List.of())));
        }

        List<Map<String, String>> legend = legendRows.stream()
            .map(r -> Map.of(
                "style",   r.getOrDefault("style", ""),
                "meaning", r.getOrDefault("meaning", "")))
            .toList();
        List<String> notes = noteRows.stream()
            .map(r -> r.getOrDefault("note", ""))
            .filter(s -> !s.isBlank())
            .toList();

        // No display name in the import format: the Template ID (slug) is used as the name.
        String slug = required(tmpl, "template_slug", "Template sheet");

        return new BbTemplateRequest(
            slug,
            slug,
            tmpl.get("agent_bank"),
            tmpl.getOrDefault("template_class", "A"),
            tmpl.get("sheet_name"),
            toZeroBased(intOrNull(tmpl.get("header_row_index"))),
            boolVal(tmpl, "auto_learned", false),
            intVal(tmpl, "tranche_count", 1),
            boolVal(tmpl, "has_grouping_rows", false),
            boolVal(tmpl, "has_color_flags", false),
            boolVal(tmpl, "auto_discover_tabs", false),
            intVal(tmpl, "summary_rows_above_header", 0),
            tmpl.get("summary_row_range"),
            intOrNull(tmpl.get("title_row")),
            tmpl.get("title_text"),
            parseCsv(tmpl.get("detect_keys"), List.of()),
            legend,
            notes,
            tabs);
    }

    // ── Sheet helpers ─────────────────────────────────────────────────────────

    private Sheet requireSheet(Workbook wb, String name) {
        Sheet s = wb.getSheet(name);
        if (s == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Import workbook is missing required sheet: \"" + name + "\"");
        return s;
    }

    private List<Map<String, String>> readOptionalRows(Workbook wb, String name) {
        Sheet s = wb.getSheet(name);
        return s != null ? readDataRows(s) : List.of();
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

        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();

        List<String> headers = new ArrayList<>();
        for (Cell c : headerRow) headers.add(cellString(c, formatter, evaluator).toLowerCase().replace(' ', '_'));

        List<Map<String, String>> result = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> map = new LinkedHashMap<>();
            boolean hasData = false;
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = row.getCell(c);
                String val = cell != null ? cellString(cell, formatter, evaluator).trim() : "";
                if (!val.isEmpty()) hasData = true;
                map.put(headers.get(c), val);
            }
            if (hasData) result.add(map);
        }
        return result;
    }

    private String cellString(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        return formatter.formatCellValue(cell, evaluator);
    }

    private String fileName(MultipartFile file) {
        return file != null && file.getOriginalFilename() != null ? file.getOriginalFilename() : "<unknown>";
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

    /** Import header-row indices are 1-based Excel rows; the engine expects 0-based. */
    private Integer toZeroBased(Integer oneBased) {
        if (oneBased == null) return null;
        return oneBased > 0 ? oneBased - 1 : oneBased;
    }

    private boolean boolVal(Map<String, String> row, String key, boolean def) {
        String v = row.get(key);
        if (v == null || v.isBlank()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "yes".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    private List<String> parseCsv(String raw, List<String> def) {
        if (raw == null || raw.isBlank()) return def;
        return Arrays.stream(raw.split(",")).map(s -> s.trim()).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return mapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // Tolerate a plain comma-separated fallback if the cell is not valid JSON.
            return parseCsv(raw, List.of());
        }
    }
}
