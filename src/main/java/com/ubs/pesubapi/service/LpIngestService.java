package com.ubs.pesubapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ubs.pesubapi.dto.IngestRequest;
import com.ubs.pesubapi.dto.IngestResult;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LpIngestService {

    private static final double MIN_FIELD_CONFIDENCE = 0.7;

    private final LpRepository              lpRepo;
    private final MatchingService           matchingService;
    private final MatchQueueEntryRepository matchQueueRepo;

    public LpIngestService(LpRepository lpRepo,
                           MatchingService matchingService,
                           MatchQueueEntryRepository matchQueueRepo) {
        this.lpRepo          = lpRepo;
        this.matchingService = matchingService;
        this.matchQueueRepo  = matchQueueRepo;
    }

    public IngestResult ingest(int submissionId, IngestRequest request) {
        List<Lp> facilityLps = lpRepo.findByFacilityIdOrderByInvestorNameAsc(request.facilityId());
        List<String> names   = facilityLps.stream().map(Lp::getInvestorName).toList();
        Map<String, Lp> byName = facilityLps.stream()
            .collect(Collectors.toMap(Lp::getInvestorName, lp -> lp, (a, b) -> a));
        MatchingService.Prepared prepared = matchingService.prepare(names);

        List<IngestResult.RecordResult> results = new ArrayList<>();
        int updated = 0, queued = 0, skipped = 0;

        for (IngestRequest.ExtractedLpRow row : request.extraction().records()) {
            if (row.investorName() == null || row.investorName().value() == null
                    || row.investorName().value().isBlank()) {
                skipped++;
                results.add(result(row.rowIndex(), null, null, null, null,
                    "Skipped", List.of(), List.of("No investor name extracted")));
                continue;
            }

            String extractedName = row.investorName().value();
            MatchingService.MatchCandidate best = matchingService.matchBest(extractedName, prepared);

            // NO_MATCH band (or no candidates) → queue as a potential new LP record (§6.4).
            if (best == null || best.band() == MatchingService.Band.NO_MATCH) {
                queued++;
                int score = best != null ? best.score() : 0;
                List<String> reasons = best == null
                    ? List.of("New LP — no matching record found in facility LP Master")
                    : List.of("New LP — best match score " + score + " is below the no-match threshold");
                results.add(result(row.rowIndex(), extractedName, null, null, score,
                    "Queued", List.of(), reasons));
                if (submissionId > 0) {
                    persistQueueEntry(submissionId, request.facilityId(), row.rowIndex(),
                        extractedName, null, null, score, reasons,
                        matchingService.analyzeTree(extractedName, prepared, 5));
                }
                continue;
            }

            Lp lp = byName.get(best.name());
            if (lp == null) {
                skipped++;
                results.add(result(row.rowIndex(), extractedName, null, best.name(), best.score(),
                    "Skipped", List.of(), List.of("Internal error: matched LP not found")));
                continue;
            }

            boolean needsReview = row.requiresReview() || !"Accept".equals(best.action());

            if (needsReview) {
                queued++;
                List<String> reasons = reviewReasons(row, best);
                results.add(result(row.rowIndex(), extractedName, lp.getId(), lp.getInvestorName(),
                    best.score(), "Queued", List.of(), reasons));
                if (submissionId > 0) {
                    persistQueueEntry(submissionId, request.facilityId(), row.rowIndex(),
                        extractedName, lp.getId(), lp.getInvestorName(), best.score(), reasons,
                        matchingService.analyzeTree(extractedName, prepared, 5));
                }
            } else {
                if (submissionId > 0) {
                    queued++;
                    results.add(result(row.rowIndex(), extractedName, lp.getId(), lp.getInvestorName(),
                        best.score(), "Queued", List.of(),
                        List.of("Auto-match queued for Match Queue name confirmation")));
                } else {
                    List<String> updatedFields = applyFields(lp, row);
                    lpRepo.save(lp);
                    updated++;
                    results.add(result(row.rowIndex(), extractedName, lp.getId(), lp.getInvestorName(),
                        best.score(), "Updated", updatedFields, List.of()));
                }
            }
        }

        String fmt = request.extraction().template() != null
            ? request.extraction().template().format() : "UNKNOWN";
        return new IngestResult(request.facilityId(), LocalDateTime.now(),
            fmt, results, updated, queued, skipped);
    }

    /**
     * Commits resolved match-queue entries to LP Master.
     * Called when the analyst advances from step 4 (Match Queue) to step 5 (Run Shadow BB).
     * Match Queue is name-only: accepted proposed matches use the chosen LP Master name, rejected
     * proposed matches use the extracted Agent BB name. No fields are copied from, or written to,
     * the matched LP record; the current facility's row is populated from Agent BB extraction only.
     */
    public void commitMatchQueueDecisions(int submissionId, int facilityId, JsonNode extractedLps) {
        if (extractedLps == null || !extractedLps.isArray()) return;

        Map<Integer, JsonNode> byRow = new HashMap<>();
        extractedLps.forEach(row -> byRow.put(row.path("rowIndex").asInt(-1), row));

        // Index existing facility LPs by name so a resolved "new" entry whose name already exists
        // (e.g. the same Agent BB committed twice) updates the record in place rather than inserting
        // a duplicate. Records created earlier in this same pass are tracked here too, so duplicate
        // resolved names within one submission also collapse onto a single record.
        Map<String, Lp> byName = lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).stream()
            .collect(Collectors.toMap(Lp::getInvestorName, lp -> lp, (a, b) -> a, HashMap::new));

        for (MatchQueueEntry entry : matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId)) {
            String decision = entry.getDecision();
            if (!"Accepted".equals(decision) && !"Rejected".equals(decision)) continue;
            JsonNode row = byRow.get(entry.getRowIndex());
            if (row == null) continue;

            boolean createAsNew = "Rejected".equals(decision) || entry.isNew();
            String name = lpNameForCommit(entry, createAsNew);
            if (name == null || name.isBlank()) continue;

            Lp lp = byName.get(name);
            if (lp == null) {
                lp = new Lp();
                lp.setFacilityId(facilityId);
                lp.setInvestorName(name);
                lp.setInvType("Institutional");
                lp.setRegion("US");
                lp.setCls("Eligible");
            }
            lp.setSourceSeq(entry.getRowIndex());   // preserve the Agent BB row order
            applyExtractedJsonRow(lp, row);
            lp = lpRepo.save(lp);
            byName.put(lp.getInvestorName(), lp);
        }
    }

    /**
     * Accepted proposed matches choose the LP Master name only. Rejected matches deliberately use
     * the Agent BB name, creating a separate current-facility LP record.
     */
    private String lpNameForCommit(MatchQueueEntry entry, boolean createAsNew) {
        if (createAsNew) return entry.getExtractedName();
        if (entry.getMasterNameOverride() != null && !entry.getMasterNameOverride().isBlank()) {
            return entry.getMasterNameOverride();
        }
        if (entry.getMatchedLpName() != null && !entry.getMatchedLpName().isBlank()) {
            return entry.getMatchedLpName();
        }
        return entry.getExtractedName();
    }

    private void applyExtractedJsonRow(Lp lp, JsonNode row) {
        String aum     = textOrNull(row, "aum");
        String commit  = textOrNull(row, "commit");
        String uncalled = textOrNull(row, "uncalled");
        String rate    = textOrNull(row, "agentRate");
        String conc    = textOrNull(row, "agentConc");
        String parent  = textOrNull(row, "parent");
        String nav     = textOrNull(row, "nav");
        String sp      = textOrNull(row, "sp");
        String mdy     = textOrNull(row, "moodys");
        String fitch   = textOrNull(row, "fitch");
        // Agent LP Category verbatim from the Agent BB (e.g. "Pension Fund", "Designated PWM",
        // "Rated Included"). Persisted here so it survives the Commit Decisions step — the bb.run and
        // classification-edit paths already set it; this path previously dropped it, leaving the agent
        // value blank in Shadow BB. It is distinct from invType (Investor Type), a manual field.
        String agentCls  = textOrNull(row, "agentClass");
        String calledCap = textOrNull(row, "calledCap");
        String pctCalled = textOrNull(row, "pctCalled");
        String pctUncalled = textOrNull(row, "pctUncalled");
        // Prefer the raw extracted agent BB column; fall back to the uncalled×rate proxy.
        String agentBB   = textOrNull(row, "agentBB");
        if (agentBB == null) agentBB = textOrNull(row, "agentBBFmt");

        if (aum      != null) lp.setAum(aum);
        if (commit   != null) lp.setCapCommit(commit);
        if (uncalled != null) lp.setUc(uncalled);
        if (rate     != null) lp.setAgentRate(rate);
        if (conc     != null) lp.setAgentConc(conc);
        if (parent   != null) lp.setParent(parent);
        if (nav      != null) lp.setNav(nav);
        if (sp       != null) lp.setSp(sp);
        if (mdy      != null) lp.setMdy(mdy);
        if (fitch    != null) lp.setFitch(fitch);
        if (agentCls != null) lp.setAgentCls(agentCls);
        if (agentBB  != null) lp.setAbb(agentBB);
        if (calledCap  != null) lp.setCalledCap(calledCap);
        if (pctCalled  != null) lp.setPctCalled(pctCalled);
        if (pctUncalled != null) lp.setPctUncalled(pctUncalled);
        lp.setUpdatedAt(LocalDateTime.now());
    }

    private String textOrNull(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }

    private void persistQueueEntry(int submissionId, int facilityId, int rowIndex,
                                   String extractedName, Integer matchedLpId,
                                   String matchedLpName, int matchScore,
                                   List<String> reasons, JsonNode matchDetails) {
        MatchQueueEntry entry = new MatchQueueEntry();
        entry.setSubmissionId(submissionId);
        entry.setFacilityId(facilityId);
        entry.setRowIndex(rowIndex);
        entry.setExtractedName(extractedName);
        entry.setMatchedLpId(matchedLpId);
        entry.setMatchedLpName(matchedLpName);
        entry.setMatchScore(matchScore);
        entry.setDecision("Pending");
        entry.setNew(matchedLpId == null);
        entry.setReasons(reasons);
        entry.setMatchDetails(matchDetails);
        matchQueueRepo.save(entry);
    }

    private List<String> applyFields(Lp lp, IngestRequest.ExtractedLpRow row) {
        List<String> changed = new ArrayList<>();
        BigDecimal aum  = valueIfValid(row.aum());
        BigDecimal comm = valueIfValid(row.commitment());
        BigDecimal uc   = valueIfValid(row.uncalled());
        BigDecimal rate = valueIfValid(row.agentRate());
        BigDecimal conc = valueIfValid(row.concentrationLimit());

        if (aum  != null) { lp.setAum(formatMoney(aum));        changed.add("aum"); }
        if (comm != null) { lp.setCapCommit(formatMoney(comm));  changed.add("capCommit"); }
        if (uc   != null) { lp.setUc(formatMoney(uc));           changed.add("uc"); }
        if (rate != null) { lp.setAgentRate(formatRate(rate));   changed.add("agentRate"); }
        if (conc != null) { lp.setAgentConc(formatRate(conc));   changed.add("agentConc"); }

        String sp       = strValueIfValid(row.sp());
        String mdy      = strValueIfValid(row.mdy());
        String fitch    = strValueIfValid(row.fitch());
        String nav      = strValueIfValid(row.nav());
        String agentCls = strValueIfValid(row.agentCls());
        String parent   = strValueIfValid(row.parent());

        if (sp       != null) { lp.setSp(sp);               changed.add("sp"); }
        if (mdy      != null) { lp.setMdy(mdy);             changed.add("mdy"); }
        if (fitch    != null) { lp.setFitch(fitch);         changed.add("fitch"); }
        if (nav      != null) { lp.setNav(nav);             changed.add("nav"); }
        if (agentCls != null) { lp.setAgentCls(agentCls);   changed.add("agentCls"); }
        if (parent   != null) { lp.setParent(parent);       changed.add("parent"); }

        lp.setUpdatedAt(LocalDateTime.now());
        return changed;
    }

    private String strValueIfValid(IngestRequest.StringField f) {
        return (f != null && f.value() != null && !f.value().isBlank()
                && f.confidence() >= MIN_FIELD_CONFIDENCE) ? f.value() : null;
    }

    private BigDecimal valueIfValid(IngestRequest.DecimalField f) {
        return (f != null && f.value() != null && f.confidence() >= MIN_FIELD_CONFIDENCE)
            ? f.value() : null;
    }

    private String formatMoney(BigDecimal v) {
        BigDecimal abs = v.abs();
        if (abs.compareTo(new BigDecimal("1000000000")) >= 0)
            return String.format("$%.1fB", v.divide(new BigDecimal("1000000000"), 1, RoundingMode.HALF_UP));
        if (abs.compareTo(new BigDecimal("1000000")) >= 0)
            return String.format("$%.1fM", v.divide(new BigDecimal("1000000"), 1, RoundingMode.HALF_UP));
        return String.format("$%.0f", v);
    }

    private String formatRate(BigDecimal v) {
        BigDecimal pct = v.compareTo(BigDecimal.ONE) < 0
            ? v.multiply(BigDecimal.valueOf(100))
            : v;
        return String.format("%.1f%%", pct);
    }

    private List<String> reviewReasons(IngestRequest.ExtractedLpRow row,
                                       MatchingService.MatchCandidate match) {
        List<String> reasons = new ArrayList<>();
        if (!"Accept".equals(match.action()))
            reasons.add("Match score " + match.score() + " is below auto-accept threshold");
        if (row.requiresReview())
            reasons.add("Extraction flagged low-confidence fields");
        if (row.warnings() != null)
            row.warnings().forEach(w -> reasons.add(w.field() + ": " + w.message()));
        return reasons;
    }

    private IngestResult.RecordResult result(int rowIndex, String extractedName,
            Integer matchedId, String matchedName, Integer matchScore,
            String action, List<String> updatedFields, List<String> warnings) {
        return new IngestResult.RecordResult(rowIndex, extractedName, matchedId,
            matchedName, matchScore, action, updatedFields, warnings);
    }
}
