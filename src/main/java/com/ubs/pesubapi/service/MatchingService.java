package com.ubs.pesubapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.repository.LpRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MatchingService {

    private final LpRepository  lpRepo;
    private final ConfigService configService;
    private final ObjectMapper  mapper;

    public MatchingService(LpRepository lpRepo, ConfigService configService, ObjectMapper mapper) {
        this.lpRepo        = lpRepo;
        this.configService = configService;
        this.mapper        = mapper;
    }

    // ── Public types ──────────────────────────────────────────────────────────

    public record MatchCandidate(String name, int score, String action) {}
    public record MatchTestResult(String input, String normalised, List<MatchCandidate> matches) {}

    /**
     * The full master candidate list, indexed once so it can be reused across every row of
     * an upload (and across threads — it is immutable and read-only after construction).
     * Three structures make the per-row match fast without dropping any candidate from
     * consideration:
     * <ul>
     *   <li><b>exact</b> — normalised name → first original (list order). An incoming row
     *       whose name already exists verbatim in master (e.g. the same Agent BB uploaded
     *       again) resolves in O(1) with score 100, skipping all fuzzy scoring.</li>
     *   <li><b>lengthOrder / lengthOf</b> — candidate indices sorted by normalised length,
     *       enabling a length-band prefilter. Given the configured weights and the review
     *       threshold, a candidate whose length differs too far from the input cannot reach
     *       that threshold even with a perfect Jaro-Winkler score, so it can be skipped
     *       without changing any Accept/Queue/Reject decision (see {@link #lengthBand}).</li>
     * </ul>
     */
    public static final class Prepared {
        private final Config                cfg;
        private final List<NormalizedName>  candidates;
        private final Map<String, String>   exact;       // normalized → first original (list order)
        private final int[]                 lengthOrder; // candidate indices, sorted by normalised length
        private final int[]                 lengthOf;    // normalised length, parallel to lengthOrder
        private final double                bandFactor;  // (1 - levMin); <= 0 disables length banding

        private Prepared(Config cfg, List<NormalizedName> candidates) {
            this.cfg        = cfg;
            this.candidates = candidates;

            Map<String, String> exactMap = new HashMap<>(candidates.size() * 2);
            Integer[] order = new Integer[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                order[i] = i;
                exactMap.putIfAbsent(candidates.get(i).normalized(), candidates.get(i).original());
            }
            this.exact = exactMap;

            Arrays.sort(order, Comparator.comparingInt(i -> candidates.get(i).normalized().length()));
            this.lengthOrder = new int[order.length];
            this.lengthOf    = new int[order.length];
            for (int k = 0; k < order.length; k++) {
                lengthOrder[k] = order[k];
                lengthOf[k]    = candidates.get(order[k]).normalized().length();
            }

            // Max achievable score given a candidate's lev similarity is jwWeight·1 + levWeight·lev.
            // To possibly reach the review threshold T, lev ≥ (T - jwWeight) / levWeight = levMin.
            // lev = 1 - editDist/maxLen, and editDist ≥ |Δlen|, so |Δlen| ≤ (1 - levMin)·maxLen.
            double t      = cfg.reviewQueue() / 100.0;
            double levMin = cfg.levWeight() > 0 ? (t - cfg.jwWeight()) / cfg.levWeight() : 0.0;
            this.bandFactor = (cfg.levWeight() > 0 && levMin > 0.0) ? (1.0 - levMin) : 0.0;
        }

        /** Candidate indices whose length could clear the review threshold for an input of {@code len}. */
        private int[] lengthBand(int len) {
            if (bandFactor <= 0.0 || bandFactor >= 1.0) {            // no safe pruning possible
                return lengthOrder;
            }
            int lo = (int) Math.floor((1.0 - bandFactor) * len);     // b ≥ (1 - band)·a
            int hi = (int) Math.ceil(len / (1.0 - bandFactor));      // b ≤ a / (1 - band)
            int from = lowerBound(lengthOf, lo);
            int to   = upperBound(lengthOf, hi);
            return Arrays.copyOfRange(lengthOrder, from, to);
        }
    }

    private record NormalizedName(String original, String normalized) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /** Parse config and index the candidate list once for reuse via {@link #matchBest}. */
    public Prepared prepare(List<String> candidates) {
        Config cfg = parseConfig();
        return new Prepared(cfg, normalizeAll(candidates, cfg));
    }

    /** Best candidate for {@code name} against a previously {@link #prepare}d list. */
    public MatchCandidate matchBest(String name, Prepared prepared) {
        if (prepared.candidates.isEmpty()) return null;
        String norm = normalize(name, prepared.cfg);

        // Fast path: identical name already in master — score 100, no fuzzy scoring.
        String exactHit = prepared.exact.get(norm);
        if (exactHit != null) return new MatchCandidate(exactHit, 100, action(100, prepared.cfg));

        // Fuzzy path: score only the length-band survivors. Pruned candidates cannot reach
        // the review threshold, so Accept/Queue decisions and matched names are unchanged.
        return scan(norm, prepared, prepared.lengthBand(norm.length()));
    }

    public MatchCandidate matchBestInList(String name, List<String> candidates) {
        if (candidates.isEmpty()) return null;
        return matchBest(name, prepare(candidates));
    }

    private List<NormalizedName> normalizeAll(List<String> names, Config cfg) {
        List<NormalizedName> out = new ArrayList<>(names.size());
        for (String n : names) out.add(new NormalizedName(n, normalize(n, cfg)));
        return out;
    }

    /**
     * Score {@code norm} against the candidates at {@code indices}, keeping the highest score and,
     * on ties, the lowest candidate index — i.e. the earliest in the original master-list order,
     * matching a sequential full scan's tie-break.
     */
    private MatchCandidate scan(String norm, Prepared p, int[] indices) {
        int bestScore = -1, bestIdx = -1;
        for (int idx : indices) {
            int s = score(norm, p.candidates.get(idx).normalized(), p.cfg);
            if (s > bestScore || (s == bestScore && idx < bestIdx)) {
                bestScore = s;
                bestIdx   = idx;
            }
        }
        if (bestIdx < 0) return null;
        return new MatchCandidate(p.candidates.get(bestIdx).original(), bestScore, action(bestScore, p.cfg));
    }

    /** Exhaustive scan over every candidate — no exact map, no length banding. Test seam / reference. */
    MatchCandidate matchBestExhaustive(String name, Prepared prepared) {
        if (prepared.candidates.isEmpty()) return null;
        String norm = normalize(name, prepared.cfg);
        int[] all = new int[prepared.candidates.size()];
        for (int i = 0; i < all.length; i++) all[i] = i;
        return scan(norm, prepared, all);
    }

    private static String action(int s, Config cfg) {
        return s >= cfg.autoAccept() ? "Accept" : s >= cfg.reviewQueue() ? "Queue" : "Reject";
    }

    /** First index in sorted {@code a} whose value is ≥ {@code key}. */
    private static int lowerBound(int[] a, int key) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid] < key) lo = mid + 1; else hi = mid; }
        return lo;
    }

    /** First index in sorted {@code a} whose value is > {@code key}. */
    private static int upperBound(int[] a, int key) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid] <= key) lo = mid + 1; else hi = mid; }
        return lo;
    }

    public @NonNull List<MatchQueueEntry> buildMatchQueueEntries(
            int submissionId, int facilityId, JsonNode extractedLps) {
        if (extractedLps == null || !extractedLps.isArray()) return new ArrayList<>();
        List<String> masterNames = lpRepo.findAllDistinctNames();
        Prepared prepared = prepare(masterNames);

        // Collect the non-blank rows with their original array position (the row index).
        record Row(int rowIndex, String agentName) {}
        List<Row> rows = new ArrayList<>();
        int index = 0;
        for (JsonNode lpNode : extractedLps) {
            String agentName = lpNode.path("name").asText("").trim();
            if (!agentName.isBlank()) rows.add(new Row(index, agentName));
            index++;
        }

        // Fuzzy matching is CPU-bound and each row is independent; Prepared is immutable, so
        // scoring rows in parallel is safe. Persistence stays out of the parallel section.
        List<MatchQueueEntry> entries = rows.parallelStream()
            .map(row -> {
                MatchCandidate best = masterNames.isEmpty() ? null : matchBest(row.agentName(), prepared);
                String matchedName = (best != null && !"Reject".equals(best.action())) ? best.name() : null;
                int    matchScore  = best != null ? best.score() : 0;
                String decision    = (best != null && "Accept".equals(best.action())) ? "Accepted" : "Pending";
                MatchQueueEntry entry = new MatchQueueEntry();
                entry.setSubmissionId(submissionId);
                entry.setFacilityId(facilityId);
                entry.setRowIndex(row.rowIndex());
                entry.setExtractedName(row.agentName());
                entry.setMatchedLpName(matchedName);
                entry.setMatchScore(matchScore);
                entry.setNew(matchedName == null);
                entry.setDecision(decision);
                return entry;
            })
            .collect(Collectors.toCollection(ArrayList::new));
        return entries;
    }

    public MatchTestResult test(String inputName) {
        Config cfg   = parseConfig();
        String norm  = normalize(inputName, cfg);
        List<String> lpNames = lpRepo.findAllDistinctNames();

        List<MatchCandidate> matches = lpNames.stream()
            .map(lpName -> {
                int    score  = score(norm, normalize(lpName, cfg), cfg);
                String action = score >= cfg.autoAccept()  ? "Accept"
                              : score >= cfg.reviewQueue() ? "Queue"
                              :                              "Reject";
                return new MatchCandidate(lpName, score, action);
            })
            .sorted(Comparator.comparingInt(MatchCandidate::score).reversed())
            .limit(10)
            .collect(Collectors.toList());

        return new MatchTestResult(inputName, norm, matches);
    }

    // ── Normalisation ─────────────────────────────────────────────────────────

    private static final Pattern NON_ALNUM  = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private String normalize(String name, Config cfg) {
        if (name == null || name.isBlank()) return "";
        String s = name.trim();

        if (cfg.abbrevExpand()) {
            for (var abbr : cfg.abbreviations()) {
                s = abbr.pattern().matcher(s).replaceAll(abbr.expansion());
            }
        }
        if (cfg.caseFold()) s = s.toLowerCase(Locale.ROOT);
        if (cfg.stripSuffixes()) {
            for (Pattern suffix : cfg.stripList()) {
                s = suffix.matcher(s).replaceAll("").trim();
            }
        }
        if (cfg.punctuation()) s = NON_ALNUM.matcher(s).replaceAll(" ");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private int score(String a, String b, Config cfg) {
        if (a.isEmpty() && b.isEmpty()) return 100;
        if (a.isEmpty() || b.isEmpty()) return 0;
        double jw  = jaroWinkler(a, b);
        double lev = levenshteinSimilarity(a, b);
        return (int) Math.round((cfg.jwWeight() * jw + cfg.levWeight() * lev) * 100);
    }

    // ── Jaro-Winkler ──────────────────────────────────────────────────────────

    private static double jaroWinkler(String s1, String s2) {
        double jaro = jaro(s1, s2);
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(s1.length(), s2.length())); i++) {
            if (s1.charAt(i) != s2.charAt(i)) break;
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    private static double jaro(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        int len1 = s1.length(), len2 = s2.length();
        int matchDist = Math.max(Math.max(len1, len2) / 2 - 1, 0);

        boolean[] s1m = new boolean[len1];
        boolean[] s2m = new boolean[len2];
        int matches = 0;

        for (int i = 0; i < len1; i++) {
            int lo = Math.max(0, i - matchDist);
            int hi = Math.min(i + matchDist + 1, len2);
            for (int j = lo; j < hi; j++) {
                if (s2m[j] || s1.charAt(i) != s2.charAt(j)) continue;
                s1m[i] = s2m[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;

        int transpositions = 0, k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1m[i]) continue;
            while (!s2m[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }
        return ((double) matches / len1
              + (double) matches / len2
              + (matches - transpositions / 2.0) / matches) / 3.0;
    }

    // ── Levenshtein ───────────────────────────────────────────────────────────

    private static double levenshteinSimilarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        return maxLen == 0 ? 1.0 : 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    private static int levenshtein(String a, String b) {
        int[] dp = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) dp[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int tmp = dp[j];
                dp[j] = a.charAt(i - 1) == b.charAt(j - 1)
                    ? prev
                    : 1 + Math.min(prev, Math.min(dp[j], dp[j - 1]));
                prev = tmp;
            }
        }
        return dp[b.length()];
    }

    // ── Config parsing ────────────────────────────────────────────────────────

    private record Abbreviation(Pattern pattern, String expansion) {}
    private record Config(
        int autoAccept, int reviewQueue,
        double jwWeight, double levWeight,
        boolean caseFold, boolean punctuation, boolean stripSuffixes, boolean abbrevExpand,
        List<Pattern> stripList, List<Abbreviation> abbreviations
    ) {}

    private Config parseConfig() {
        JsonNode root = configService.get("matching_config").orElseGet(mapper::createObjectNode);
        JsonNode t    = root.path("thresholds");

        int     autoAccept   = t.path("autoAccept").asInt(95);
        int     reviewQueue  = t.path("reviewQueue").asInt(80);
        double  jwWeight     = t.path("jwWeight").asDouble(0.6);
        double  levWeight    = t.path("levWeight").asDouble(0.4);
        boolean caseFold     = t.path("caseFold").asBoolean(true);
        boolean punctuation  = t.path("punctuation").asBoolean(true);
        boolean stripSuf     = t.path("stripSuffixes").asBoolean(true);
        boolean abbrevExp    = t.path("abbrevExpand").asBoolean(true);

        List<Pattern> stripList = new ArrayList<>();
        for (JsonNode s : root.path("legalSuffixes")) {
            if (s.path("strip").asBoolean()) {
                stripList.add(Pattern.compile(
                    "(?i),?\\s*\\b" + Pattern.quote(s.path("abbr").asText()) + "\\b\\.?\\s*$"));
            }
        }

        List<Abbreviation> abbreviations = new ArrayList<>();
        for (JsonNode a : root.path("knownAbbreviations")) {
            abbreviations.add(new Abbreviation(
                Pattern.compile("(?i)\\b" + Pattern.quote(a.path("token").asText()) + "\\b"),
                a.path("expansion").asText()));
        }

        return new Config(autoAccept, reviewQueue, jwWeight, levWeight,
            caseFold, punctuation, stripSuf, abbrevExp, stripList, abbreviations);
    }
}
