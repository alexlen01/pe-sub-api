package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.TemplateProposal;
import com.ubs.pesubapi.dto.TemplateProposal.ProfiledField;
import com.ubs.pesubapi.dto.TemplateProposal.ProfiledTab;
import com.ubs.pesubapi.dto.TemplateProposal.ProposedGroup;
import com.ubs.pesubapi.dto.WorkbookSignals;
import com.ubs.pesubapi.dto.WorkbookSignals.SheetSignals;
import com.ubs.pesubapi.repository.FmAliasRepository;
import com.ubs.pesubapi.repository.FmCanonicalFieldRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, no-AI template profiler ("self-adoption").
 *
 * <p>Given the structural signals of an uploaded Agent BB workbook, it proposes a template
 * definition by pattern-matching against the Field Mapping Dictionary and a small set of generic
 * structural heuristics — header-row detection by alias scoring, column→canonical matching, tab
 * counting, summary-block label/value scraping for the agent name, and LP-category banner detection
 * mapped to canonical classifications by generic category keywords.
 *
 * <p>It contains <strong>no</strong> agent-bank or fund names — only generic LP-category keywords
 * (rated / non-rated / designated / excluded). Structural facts are high-confidence; semantic
 * guesses are flagged for operator confirmation.
 */
@Service
public class TemplateProfiler {

    private static final int MIN_HEADER_MATCHES = 3;
    private static final int HEADER_SCAN_ROWS   = 15;
    private static final int MAX_GROUPS         = 12;

    // Generic labels (not agent/fund names) that precede an agent value in the summary block.
    private static final List<String> AGENT_LABELS =
        List.of("agent bank", "agent", "administrative agent", "administered by", "prepared by", "lender");

    private static final List<String> SKIP_BANNERS =
        List.of("total", "subtotal", "sub total", "grand total", "sum", "net total");

    private final FmCanonicalFieldRepository canonicalRepo;
    private final FmAliasRepository          aliasRepo;

    public TemplateProfiler(FmCanonicalFieldRepository canonicalRepo, FmAliasRepository aliasRepo) {
        this.canonicalRepo = canonicalRepo;
        this.aliasRepo     = aliasRepo;
    }

    public TemplateProposal profile(WorkbookSignals signals, String agentBank) {
        List<String> aliasNorms = buildAliasSet();
        List<SheetSignals> sheets = signals.sheetsOrEmpty();

        List<ProfiledTab> grids = new ArrayList<>();
        SheetSignals firstGrid = null;
        int firstGridHeader = -1;
        for (SheetSignals sheet : sheets) {
            int[] scan = findHeaderRow(sheet, aliasNorms);   // [rowIndex, matched] or null
            if (scan == null) continue;
            int headerRow = scan[0];
            List<String> columns = nonBlank(rowAt(sheet, headerRow));
            grids.add(new ProfiledTab(
                sheet.name(),
                ProfiledField.of(headerRow, "high",
                    "alias-scored header row (" + scan[1] + " canonical matches)"),
                columns, scan[1], detectGroups(sheet, headerRow)));
            if (firstGrid == null) { firstGrid = sheet; firstGridHeader = headerRow; }
        }

        boolean multiple = grids.size() > 1;
        ProfiledField<String> agent = inferAgent(agentBank, firstGrid, firstGridHeader);
        ProfiledField<String> title = inferTitle(firstGrid);
        String slug = slugFromFilename(signals.fileName());
        List<String> detectKeys = detectKeys(slug, title.value());

        String overall = grids.isEmpty() ? "low"
            : (grids.get(0).matchedCanonical() >= MIN_HEADER_MATCHES ? "high" : "medium");

        return new TemplateProposal(
            ProfiledField.of(slug, slug.isBlank() ? "low" : "high", "derived from filename"),
            agent,
            title,
            ProfiledField.of(multiple ? "multiple" : "single", multiple ? "medium" : "high",
                grids.size() + " grid sheet(s) detected"),
            grids,
            ProfiledField.of(false, "low",
                "assumed named tabs — confirm if tab names vary per workbook (auto-discover)"),
            detectKeys,
            overall);
    }

    // ── Header detection ────────────────────────────────────────────────────────

    /** Returns [rowIndex, matchedCount] of the best alias-scoring row, or null if not an LP grid. */
    private int[] findHeaderRow(SheetSignals sheet, List<String> aliasNorms) {
        List<List<String>> rows = sheet.rows();
        if (rows == null) return null;
        int bestRow = -1, bestMatched = 0;
        int limit = Math.min(HEADER_SCAN_ROWS, rows.size());
        for (int i = 0; i < limit; i++) {
            int matched = 0;
            for (String cell : rows.get(i)) {
                if (cell != null && !cell.isBlank() && matchesAlias(normalize(cell), aliasNorms)) matched++;
            }
            if (matched > bestMatched) { bestMatched = matched; bestRow = i; }
        }
        return bestMatched >= MIN_HEADER_MATCHES ? new int[]{bestRow, bestMatched} : null;
    }

    private boolean matchesAlias(String normCell, List<String> aliasNorms) {
        if (normCell.isBlank()) return false;
        for (String a : aliasNorms) {
            if (a.isBlank()) continue;
            if (normCell.equals(a)) return true;
            if (a.length() >= 4 && a.contains(" ") && normCell.contains(a)) return true;
            if (normCell.length() >= 4 && normCell.contains(" ") && a.contains(normCell)) return true;
        }
        return false;
    }

    private List<String> buildAliasSet() {
        Set<String> set = new LinkedHashSet<>();
        canonicalRepo.findAll().forEach(c -> set.add(normalize(c.getCanonical())));
        aliasRepo.findAll().forEach(a -> set.add(normalize(a.getAliasText())));
        set.remove("");
        return List.copyOf(set);
    }

    // ── Group (LP-category banner) detection ────────────────────────────────────

    private List<ProposedGroup> detectGroups(SheetSignals sheet, int headerRow) {
        List<List<String>> rows = sheet.rows();
        List<ProposedGroup> groups = new ArrayList<>();
        for (int i = headerRow + 1; i < rows.size() && groups.size() < MAX_GROUPS; i++) {
            List<String> cells = rows.get(i);
            List<String> populated = nonBlank(cells);
            if (populated.size() != 1) continue;            // banners have a single populated cell
            String text = populated.get(0).trim();
            String norm = normalize(text);
            if (norm.length() < 3 || norm.length() > 60) continue;
            if (isNumeric(text)) continue;
            if (SKIP_BANNERS.stream().anyMatch(norm::contains)) continue;
            String[] cls = classifyGroup(norm, text);
            groups.add(new ProposedGroup(text, cls[0], cls[1]));
        }
        return groups;
    }

    /** Maps a banner's text to a canonical LP Classification using generic category keywords. */
    private String[] classifyGroup(String norm, String verbatim) {
        if (norm.contains("exclud") || norm.contains("ineligible"))      return new String[]{"Ineligible Investors", "medium"};
        if (norm.contains("designated") && norm.contains("pwm"))         return new String[]{"Designated PWM", "medium"};
        if (norm.contains("designated") || norm.contains("institutional")) return new String[]{"Designated Institutional", "medium"};
        if ((norm.contains("non") && norm.contains("rated")) || norm.contains("unrated"))
                                                                          return new String[]{"Non-Rated Included", "medium"};
        if (norm.contains("rated") || norm.contains("includ"))           return new String[]{"Rated Included", "medium"};
        return new String[]{verbatim, "low"};               // verbatim self-map (e.g. sleeve/feeder sections)
    }

    // ── Agent / title / slug ────────────────────────────────────────────────────

    private ProfiledField<String> inferAgent(String agentBank, SheetSignals sheet, int headerRow) {
        if (agentBank != null && !agentBank.isBlank()) {
            return ProfiledField.of(agentBank.trim(), "high", "user-entered at upload");
        }
        if (sheet != null) {
            List<List<String>> rows = sheet.rows();
            int limit = Math.min(headerRow >= 0 ? headerRow : rows.size(), rows.size());
            for (int i = 0; i < limit; i++) {
                List<String> cells = rows.get(i);
                for (int c = 0; c < cells.size(); c++) {
                    String norm = normalize(cells.get(c));
                    if (AGENT_LABELS.contains(norm)) {
                        for (int n = c + 1; n < cells.size(); n++) {
                            String v = cells.get(n);
                            if (v != null && !v.isBlank()) {
                                return ProfiledField.of(v.trim(), "medium",
                                    "summary-block label \"" + cells.get(c).trim() + "\"");
                            }
                        }
                    }
                }
            }
        }
        return ProfiledField.of(null, "low", "no agent label found — enter manually");
    }

    private ProfiledField<String> inferTitle(SheetSignals sheet) {
        if (sheet != null) {
            List<List<String>> rows = sheet.rows();
            for (int i = 0; i < Math.min(2, rows.size()); i++) {
                for (String cell : rows.get(i)) {
                    if (cell != null && !cell.isBlank() && cell.trim().length() >= 4) {
                        return ProfiledField.of(cell.trim(), "medium", "first non-empty cell in row " + (i + 1));
                    }
                }
            }
        }
        return ProfiledField.of(null, "low", "no title cell found");
    }

    private String slugFromFilename(String fileName) {
        if (fileName == null) return "";
        String base = fileName.replaceFirst("(?i)\\.(xlsx|xls|csv)$", "");
        String slug = base.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceFirst("^(agent-bb|bb-template-import|bb-template)-", "")
            .replaceAll("(^-+|-+$)", "");
        // bb_templates.template_slug is VARCHAR(50); long real-world filenames must not
        // overflow it. Trim at a word boundary rather than mid-token.
        if (slug.length() > 50) {
            slug = slug.substring(0, 50).replaceAll("-+[^-]*$", "");
        }
        return slug;
    }

    private List<String> detectKeys(String slug, String title) {
        Set<String> keys = new LinkedHashSet<>();
        if (title != null && !title.isBlank()) keys.add(normalize(title));
        if (slug != null && !slug.isBlank()) keys.add(slug.replace('-', ' '));
        keys.remove("");
        return new ArrayList<>(keys);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private List<String> rowAt(SheetSignals sheet, int i) {
        List<List<String>> rows = sheet.rows();
        return (rows != null && i >= 0 && i < rows.size()) ? rows.get(i) : List.of();
    }

    private List<String> nonBlank(List<String> cells) {
        List<String> out = new ArrayList<>();
        if (cells != null) for (String c : cells) if (c != null && !c.isBlank()) out.add(c.trim());
        return out;
    }

    private boolean isNumeric(String s) {
        String t = s.replaceAll("[,$%\\s]", "");
        if (t.isBlank()) return false;
        try { Double.parseDouble(t); return true; } catch (NumberFormatException e) { return false; }
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }
}
