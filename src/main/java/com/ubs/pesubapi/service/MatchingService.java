package com.ubs.pesubapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.repository.LpRepository;
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

    // ── Public API ────────────────────────────────────────────────────────────

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

    private String normalize(String name, Config cfg) {
        if (name == null || name.isBlank()) return "";
        String s = name.trim();

        if (cfg.abbrevExpand()) {
            for (var abbr : cfg.abbreviations()) {
                s = s.replaceAll("(?i)\\b" + Pattern.quote(abbr.token()) + "\\b", abbr.expansion());
            }
        }
        if (cfg.caseFold()) s = s.toLowerCase(Locale.ROOT);
        if (cfg.stripSuffixes()) {
            for (String suffix : cfg.stripList()) {
                s = s.replaceAll("(?i),?\\s*\\b" + Pattern.quote(suffix) + "\\b\\.?\\s*$", "").trim();
            }
        }
        if (cfg.punctuation()) s = s.replaceAll("[^a-z0-9 ]", " ");
        return s.replaceAll("\\s+", " ").trim();
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

    private record Abbreviation(String token, String expansion) {}
    private record Config(
        int autoAccept, int reviewQueue,
        double jwWeight, double levWeight,
        boolean caseFold, boolean punctuation, boolean stripSuffixes, boolean abbrevExpand,
        List<String> stripList, List<Abbreviation> abbreviations
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

        List<String> stripList = new ArrayList<>();
        for (JsonNode s : root.path("legalSuffixes")) {
            if (s.path("strip").asBoolean()) stripList.add(s.path("abbr").asText());
        }

        List<Abbreviation> abbreviations = new ArrayList<>();
        for (JsonNode a : root.path("knownAbbreviations")) {
            abbreviations.add(new Abbreviation(a.path("token").asText(), a.path("expansion").asText()));
        }

        return new Config(autoAccept, reviewQueue, jwWeight, levWeight,
            caseFold, punctuation, stripSuf, abbrevExp, stripList, abbreviations);
    }
}
