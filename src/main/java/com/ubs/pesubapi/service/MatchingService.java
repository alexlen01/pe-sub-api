package com.ubs.pesubapi.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.repository.LpMasterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final LpMasterRepository lpMasterRepo;
    private final ConfigService      configService;
    private final ObjectMapper       mapper;

    public MatchingService(LpMasterRepository lpMasterRepo, ConfigService configService, ObjectMapper mapper) {
        this.lpMasterRepo  = lpMasterRepo;
        this.configService = configService;
        this.mapper        = mapper;
    }

    // ── Public types ──────────────────────────────────────────────────────────

    /**
     * Confidence band a combined score falls into, per Solution Design §6.4.
     * <ul>
     *   <li>{@code AUTO_ACCEPT} — score ≥ autoAccept (default 95): committed without review.</li>
     *   <li>{@code REVIEW_HIGH} — reviewQueue ≤ score &lt; autoAccept (default 80–94): CO confirms
     *       the algorithm's single top candidate.</li>
     *   <li>{@code REVIEW_LOW}  — noMatch ≤ score &lt; reviewQueue (default 50–79): CO reviews a
     *       ranked candidate list and selects one or creates a new LP.</li>
     *   <li>{@code NO_MATCH}    — score &lt; noMatch (default 50): queued as a potential new LP record.</li>
     * </ul>
     * Note: only {@code AUTO_ACCEPT} is resolved automatically; the other three bands are all
     * queued for the credit officer — nothing is auto-rejected.
     */
    public enum Band { AUTO_ACCEPT, REVIEW_HIGH, REVIEW_LOW, NO_MATCH }

    public record MatchCandidate(String name, int score, String action, Band band) {}
    public record MatchTestResult(String input, String normalised, List<MatchCandidate> matches) {}

    /** One scored LP Master candidate with its per-metric breakdown, for the Match Analysis panel (§6.5). */
    public record ScoredCandidate(String name, int jw, int lev, int combined, Band band) {}

    /**
     * Full match-analysis payload persisted as {@code match_details} JSONB on each queue entry (§6.5):
     * the normalised agent name, the winning band, and the ranked top-N candidate breakdown.
     */
    public record MatchAnalysis(String agentName, String normalized, Band band, List<ScoredCandidate> candidates) {}

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
            // To possibly reach the lowest "show a candidate" threshold T (noMatch — below it the
            // item is queued as a new LP with no candidate), lev ≥ (T - jwWeight) / levWeight = levMin.
            // lev = 1 - editDist/maxLen, and editDist ≥ |Δlen|, so |Δlen| ≤ (1 - levMin)·maxLen.
            double t      = cfg.noMatch() / 100.0;
            double levMin = cfg.levWeight() > 0 ? (t - cfg.jwWeight()) / cfg.levWeight() : 0.0;
            this.bandFactor = (cfg.levWeight() > 0 && levMin > 0.0) ? (1.0 - levMin) : 0.0;
        }

        /** Candidate indices whose length could clear the lowest match threshold (noMatch) for an input of {@code len}. */
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
        if (exactHit != null) return candidate(exactHit, 100, prepared.cfg);

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
        return candidate(p.candidates.get(bestIdx).original(), bestScore, p.cfg);
    }

    /** Exhaustive scan over every candidate — no exact map, no length banding. Test seam / reference. */
    MatchCandidate matchBestExhaustive(String name, Prepared prepared) {
        if (prepared.candidates.isEmpty()) return null;
        String norm = normalize(name, prepared.cfg);
        int[] all = new int[prepared.candidates.size()];
        for (int i = 0; i < all.length; i++) all[i] = i;
        return scan(norm, prepared, all);
    }

    /** Build a candidate carrying its score, confidence band, and human-facing action label. */
    private static MatchCandidate candidate(String name, int score, Config cfg) {
        Band band = band(score, cfg);
        return new MatchCandidate(name, score, action(band), band);
    }

    /** Classify a combined score into one of the four confidence bands (§6.4). */
    private static Band band(int s, Config cfg) {
        if (s >= cfg.autoAccept())  return Band.AUTO_ACCEPT;
        if (s >= cfg.reviewQueue()) return Band.REVIEW_HIGH;
        if (s >= cfg.noMatch())     return Band.REVIEW_LOW;
        return Band.NO_MATCH;
    }

    /**
     * Human-facing action label for a band. {@code AUTO_ACCEPT} → "Accept"; the two review bands →
     * "Review" (queued, candidate shown); {@code NO_MATCH} → "New" (queued as a potential new LP).
     */
    private static String action(Band band) {
        return switch (band) {
            case AUTO_ACCEPT            -> "Accept";
            case REVIEW_HIGH, REVIEW_LOW -> "Review";
            case NO_MATCH               -> "New";
        };
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

    public List<MatchQueueEntry> buildMatchQueueEntries(
            int submissionId, int facilityId, JsonNode extractedLps) {
        if (extractedLps == null || !extractedLps.isArray()) return new ArrayList<>();
        List<String> masterNames = lpMasterRepo.findAllInvestorNames();
        Prepared prepared = prepare(masterNames);

        // Collect non-blank rows using the extraction row's stable sequence id when present.
        // Multi-tab workbooks (Audax VII, CCP VII, etc.) restart worksheet row numbers on each tab,
        // so raw rowIndex is not unique and cannot be the queue/order key. The API assigns `id`
        // sequentially across the combined LP Data Extract array, preserving tab order + row order.
        record Row(int rowIndex, String agentName) {}
        List<Row> rows = new ArrayList<>();
        int index = 0;
        for (JsonNode lpNode : extractedLps) {
            String agentName = lpNode.path("name").asString("").trim();
            int rowIndex = lpNode.path("id").asInt(lpNode.path("rowIndex").asInt(index));
            if (!agentName.isBlank()) rows.add(new Row(rowIndex, agentName));
            index++;
        }

        // Fuzzy matching is CPU-bound and each row is independent; Prepared is immutable, so
        // scoring rows in parallel is safe. Persistence stays out of the parallel section.
        Config cfg = prepared.cfg;
        List<MatchQueueEntry> entries = new ArrayList<>(rows.parallelStream()
            .map(row -> {
                // Full match analysis drives the decision (§6.4) and the persisted breakdown (§6.5).
                MatchAnalysis analysis = masterNames.isEmpty() ? null : analyze(row.agentName(), prepared, 5);
                ScoredCandidate top = (analysis != null && !analysis.candidates().isEmpty())
                    ? analysis.candidates().getFirst() : null;
                Band   band        = top != null ? top.band() : Band.NO_MATCH;
                boolean isNew      = band == Band.NO_MATCH;          // below noMatch → potential new LP
                String matchedName = (top != null && !isNew) ? top.name() : null;      // review/accept bands show a candidate
                int    matchScore  = top != null ? top.combined() : 0;
                String decision    = band == Band.AUTO_ACCEPT ? "Accepted" : "Pending";

                MatchQueueEntry entry = new MatchQueueEntry();
                entry.setSubmissionId(submissionId);
                entry.setFacilityId(facilityId);
                entry.setRowIndex(row.rowIndex());
                entry.setExtractedName(row.agentName());
                entry.setMatchedLpName(matchedName);
                entry.setMatchScore(matchScore);
                entry.setNew(isNew);
                entry.setDecision(decision);
                entry.setReasons(queueReasons(band, matchScore, cfg));
                if (analysis != null) entry.setMatchDetails(mapper.valueToTree(analysis));
                // DEBUG identifies each queue entry so a persistence failure on save is
                // attributable to a specific extracted row in lower environments.
                log.debug("Match queue entry built: submission={} rowIndex={} extractedName='{}' "
                        + "matchedName='{}' score={} band={} decision={}",
                    submissionId, row.rowIndex(), row.agentName(), matchedName, matchScore, band, decision);
                return entry;
            })
            .toList());
        return entries;
    }

    public MatchTestResult test(String inputName) {
        Config cfg   = parseConfig();
        String norm  = normalize(inputName, cfg);
        List<String> lpNames = lpMasterRepo.findAllInvestorNames();

        List<MatchCandidate> matches = lpNames.stream()
            .map(lpName -> candidate(lpName, score(norm, normalize(lpName, cfg), cfg), cfg))
            .sorted(Comparator.comparingInt((MatchCandidate candidate) -> candidate.score()).reversed())
            .limit(10)
            .collect(Collectors.toList());

        return new MatchTestResult(inputName, norm, matches);
    }

    /**
     * Ranked match analysis for one agent name against a prepared candidate list (§6.5). Scores the
     * length-band survivors, returns the top-{@code topN} by combined score — tie-broken to the
     * earliest LP Master order, mirroring {@link #scan}'s winner — with each candidate's Jaro-Winkler,
     * Levenshtein and combined scores and confidence band. The overall band is the top candidate's.
     */
    public MatchAnalysis analyze(String agentName, Prepared prepared, int topN) {
        Config cfg  = prepared.cfg;
        String norm = normalize(agentName, cfg);
        if (prepared.candidates.isEmpty())
            return new MatchAnalysis(agentName, norm, Band.NO_MATCH, List.of());

        int[]     indices    = prepared.lengthBand(norm.length());
        Integer[] order      = new Integer[indices.length];
        int[]     combinedOf = new int[indices.length];
        for (int k = 0; k < indices.length; k++) {
            order[k]      = k;
            combinedOf[k] = score(norm, prepared.candidates.get(indices[k]).normalized(), cfg);
        }
        // combined desc, then earliest master order on ties — same winner as the single-best scan.
        Arrays.sort(order, (x, y) -> combinedOf[(int) x] != combinedOf[(int) y]
            ? Integer.compare(combinedOf[(int) y], combinedOf[(int) x])
            : Integer.compare(indices[(int) x], indices[(int) y]));

        List<ScoredCandidate> top = new ArrayList<>(Math.min(topN, order.length));
        for (int k = 0; k < order.length && top.size() < topN; k++) {
            NormalizedName c = prepared.candidates.get(indices[order[k]]);
            int combined = combinedOf[order[k]];
            int jw  = (int) Math.round(jaroWinkler(norm, c.normalized()) * 100);
            int lev = (int) Math.round(levenshteinSimilarity(norm, c.normalized()) * 100);
            top.add(new ScoredCandidate(c.original(), jw, lev, combined, band(combined, cfg)));
        }
        Band overall = top.isEmpty() ? Band.NO_MATCH : top.getFirst().band();
        return new MatchAnalysis(agentName, norm, overall, top);
    }

    /** {@link #analyze} serialised to a JSON tree for persisting as {@code match_details} JSONB. */
    public JsonNode analyzeTree(String agentName, Prepared prepared, int topN) {
        return mapper.valueToTree(analyze(agentName, prepared, topN));
    }

    /** Human-readable reasons for the Match Queue, derived from the winning confidence band (§6.4). */
    private static List<String> queueReasons(Band band, int score, Config cfg) {
        return switch (band) {
            case AUTO_ACCEPT -> List.of("Auto-accepted — score " + score + " ≥ " + cfg.autoAccept());
            case REVIEW_HIGH -> List.of("High-confidence review — score " + score + " in "
                + cfg.reviewQueue() + "–" + (cfg.autoAccept() - 1) + "; confirm top candidate");
            case REVIEW_LOW  -> List.of("Low-confidence review — score " + score + " in "
                + cfg.noMatch() + "–" + (cfg.reviewQueue() - 1) + "; select a candidate or create a new LP");
            case NO_MATCH    -> List.of("No match — best score " + score + " < " + cfg.noMatch()
                + "; confirm creation of a new LP Master record");
        };
    }

    // ── Normalisation ─────────────────────────────────────────────────────────

    private static final Pattern NON_ALNUM  = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Pipeline step 6 (§6.2): retirement-suffix normalization. Pension/retirement LP names are
     * abbreviated inconsistently by agent banks ("Texas Teachers Ret. Sys."). These rules fold the
     * common forms onto a canonical spelling so they match the LP Master. Dot- and space-separated
     * variants are both handled; the "Ret. Sys." rule must run before the bare "Ret." rule.
     */
    private record RetireRule(Pattern pattern, String replacement) {}
    private static final List<RetireRule> RETIREMENT_RULES = List.of(
        new RetireRule(Pattern.compile("(?i)\\bret(?:irement)?\\.?\\s+sys(?:tem|\\.)?(?=\\s|$)"), "retirement system"),
        new RetireRule(Pattern.compile("(?i)\\bret\\.(?=\\s|$)"),                                  "retirement")
    );

    private String normalize(String name, Config cfg) {
        if (name == null || name.isBlank()) return "";
        String s = name.trim();

        if (cfg.abbrevExpand()) {
            for (var abbr : cfg.abbreviations()) {
                s = abbr.pattern().matcher(s).replaceAll(abbr.expansion());
            }
        }
        if (cfg.retirementNormalize()) {
            for (RetireRule rule : RETIREMENT_RULES) {
                s = rule.pattern().matcher(s).replaceAll(rule.replacement());
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
        int autoAccept, int reviewQueue, int noMatch,
        double jwWeight, double levWeight,
        boolean caseFold, boolean punctuation, boolean stripSuffixes, boolean abbrevExpand,
        boolean retirementNormalize,
        List<Pattern> stripList, List<Abbreviation> abbreviations
    ) {}

    private Config parseConfig() {
        JsonNode root = configService.get("matching_config").orElseGet(mapper::createObjectNode);
        JsonNode t    = root.path("thresholds");

        int     autoAccept   = t.path("autoAccept").asInt(95);
        int     reviewQueue  = t.path("reviewQueue").asInt(80);
        int     noMatch      = t.path("noMatch").asInt(50);
        double  jwWeight     = t.path("jwWeight").asDouble(0.6);
        double  levWeight    = t.path("levWeight").asDouble(0.4);
        boolean caseFold     = t.path("caseFold").asBoolean(true);
        boolean punctuation  = t.path("punctuation").asBoolean(true);
        boolean stripSuf     = t.path("stripSuffixes").asBoolean(true);
        boolean abbrevExp    = t.path("abbrevExpand").asBoolean(true);
        boolean retireNorm   = t.path("retirementNormalize").asBoolean(true);

        List<Pattern> stripList = new ArrayList<>();
        for (JsonNode s : root.path("legalSuffixes")) {
            if (s.path("strip").asBoolean()) {
                stripList.add(Pattern.compile(
                    "(?i),?\\s*\\b" + Pattern.quote(s.path("abbr").asString()) + "\\b\\.?\\s*$"));
            }
        }

        List<Abbreviation> abbreviations = new ArrayList<>();
        for (JsonNode a : root.path("knownAbbreviations")) {
            abbreviations.add(new Abbreviation(
                Pattern.compile("(?i)\\b" + Pattern.quote(a.path("token").asString()) + "\\b"),
                a.path("expansion").asString()));
        }

        return new Config(autoAccept, reviewQueue, noMatch, jwWeight, levWeight,
            caseFold, punctuation, stripSuf, abbrevExp, retireNorm, stripList, abbreviations);
    }
}
