package com.ubs.pesubapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ubs.pesubapi.dto.IngestRequest;
import com.ubs.pesubapi.dto.IngestResult;
import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final LpMasterRepository        lpMasterRepo;
    private final MatchingService           matchingService;
    private final MatchQueueEntryRepository matchQueueRepo;

    public LpIngestService(LpRepository lpRepo,
                           LpMasterRepository lpMasterRepo,
                           MatchingService matchingService,
                           MatchQueueEntryRepository matchQueueRepo) {
        this.lpRepo          = lpRepo;
        this.lpMasterRepo    = lpMasterRepo;
        this.matchingService = matchingService;
        this.matchQueueRepo  = matchQueueRepo;
    }

    public IngestResult ingest(int submissionId, IngestRequest request) {
        List<Lp> facilityLps = lpRepo.findByFacilityIdOrderByInvestorNameAsc(request.facilityId());
        List<String> names   = facilityLps.stream().map(lp -> lp.getInvestorName()).toList();
        Map<String, Lp> byName = facilityLps.stream()
            .collect(Collectors.toMap(lp -> lp.getInvestorName(), lp -> lp, (a, b) -> a));
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
     * Commits all extracted LP rows to the facility, replacing any prior records for that facility.
     * Called when the analyst advances from step 4 (Match Queue) to step 5 (Run Shadow BB).
     *
     * Every extracted row is inserted regardless of its match queue decision:
     * - Accepted (matched to LP Master): uses the LP Master name; empty fields pre-populated
     *   from LP Master stable identity, ratings, and UBS credit profile before extraction wins.
     * - All other rows (Rejected, Pending, New, or no queue entry): use the extracted name;
     *   no LP Master enrichment applied.
     *
     * Existing facility LP records are deleted before insertion so a re-run produces an exact
     * 1-to-1 replacement rather than a merge with stale records.
     */
    @Transactional
    public void commitMatchQueueDecisions(int submissionId, int facilityId, JsonNode extractedLps) {
        if (extractedLps == null || !extractedLps.isArray()) return;

        Map<Integer, JsonNode> byRow = new HashMap<>();
        extractedLps.forEach(node -> byRow.put(node.path("rowIndex").asInt(-1), node));

        Map<Integer, MatchQueueEntry> queueByRow =
            matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId).stream()
                .collect(Collectors.toMap(e -> e.getRowIndex(), e -> e, (a, b) -> a, HashMap::new));

        // Replace all existing facility LP records — this submission is authoritative.
        lpRepo.deleteByFacilityId(facilityId);

        // Insert every extracted row; no row is discarded regardless of match queue status.
        byRow.keySet().stream().sorted().forEach(rowIndex -> {
            JsonNode row = byRow.get(rowIndex);
            String extractedName = row.path("name").asText("").trim();
            if (extractedName.isBlank()) return;

            MatchQueueEntry entry = queueByRow.get(rowIndex);
            boolean isAccepted = entry != null
                && "Accepted".equals(entry.getDecision())
                && !entry.isNew();
            String name = isAccepted ? lpNameForCommit(entry, false) : extractedName;
            if (name == null || name.isBlank()) name = extractedName;

            Lp lp = new Lp();
            lp.setFacilityId(facilityId);
            lp.setInvestorName(name);
            lp.setInvestorType("");
            lp.setInstVsHnw("Institutional");
            lp.setRegion("US");
            lp.setCls("Eligible");

            // For accepted LP Master matches: pre-populate empty fields from LP Master
            // (stable identity, ratings, UBS credit profile) before extraction fields win.
            if (isAccepted) {
                final Lp lpRef = lp;
                lpMasterRepo.findByInvestorName(name).ifPresent(m -> applyLpMasterBaseline(lpRef, m));
            }

            lp.setSourceSeq(rowIndex);
            applyExtractedJsonRow(lp, row);
            lpRepo.save(lp);
        });
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
        // value blank in Shadow BB. It is distinct from Investor Type, a manual field.
        String agentCls  = textOrNull(row, "agentClass");
        String calledCap = textOrNull(row, "calledCap");
        String pctCalled = textOrNull(row, "pctCalled");
        String pctUncalled = textOrNull(row, "pctUncalled");
        String notes   = textOrNull(row, "notes");
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
        if (notes     != null) lp.setNotes(notes);
        lp.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Applies LP Master stable attributes and UBS credit profile as a baseline onto a
     * facility LP record before Agent BB extraction fields are overlaid.
     *
     * Stable identity/financial scale fields are applied only when the LP record currently
     * holds its default/blank value, so a manually-edited facility record is not silently
     * clobbered.  UBS credit profile fields (cls, ubsRate, ubsConc) are applied
     * unconditionally — they are never set by extraction, so LP Master is the only source
     * of pre-populated credit decisions for this submission cycle.
     */
    private void applyLpMasterBaseline(Lp lp, LpMaster master) {
        // Stable identity — override defaults set at new-record creation time
        if (master.getInvestorType() != null && !master.getInvestorType().isBlank()
                && (lp.getInvestorType() == null || lp.getInvestorType().isBlank())) {
            lp.setInvestorType(master.getInvestorType());
        }
        if (master.getInstVsHnw() != null && !master.getInstVsHnw().isBlank()
                && (lp.getInstVsHnw() == null || lp.getInstVsHnw().isBlank())) {
            lp.setInstVsHnw(master.getInstVsHnw());
        }
        if (master.getRegion() != null && !master.getRegion().isBlank()
                && "US".equals(lp.getRegion())) {
            lp.setRegion(master.getRegion());
        }
        lp.setSpv(master.isSpv());
        lp.setHighQty(master.isHighQty());
        lp.setIg(master.isIg());
        if (master.getParent() != null && !master.getParent().isBlank() && lp.getParent() == null) {
            lp.setParent(master.getParent());
        }

        // Ratings — apply when LP Master has a value and the facility record is still blank
        if (!master.getSp().isBlank()    && lp.getSp().isBlank())    lp.setSp(master.getSp());
        if (!master.getMdy().isBlank()   && lp.getMdy().isBlank())   lp.setMdy(master.getMdy());
        if (!master.getFitch().isBlank() && lp.getFitch().isBlank()) lp.setFitch(master.getFitch());

        // Financial scale — fill nulls from LP Master
        if (master.getAum()          != null && lp.getAum()          == null) lp.setAum(master.getAum());
        if (master.getNav()          != null && lp.getNav()          == null) lp.setNav(master.getNav());
        if (master.getPension()      != null && lp.getPension()      == null) lp.setPension(master.getPension());
        if (master.getPensionFunded()!= null && lp.getPensionFunded()== null) lp.setPensionFunded(master.getPensionFunded());

        // UBS credit profile — always apply; these fields are never set by extraction so
        // LP Master is the sole pre-populated source ahead of the credit officer's review
        if (master.getUbsClassification() != null && !master.getUbsClassification().isBlank()) {
            lp.setCls(master.getUbsClassification());
        }
        if (master.getUbsDefaultAdvRate() != null && !master.getUbsDefaultAdvRate().isBlank()) {
            lp.setUbsRate(master.getUbsDefaultAdvRate());
        }
        if (master.getUbsDefaultConcLimit() != null && !master.getUbsDefaultConcLimit().isBlank()) {
            lp.setUbsConc(master.getUbsDefaultConcLimit());
        }
    }

    /**
     * Writes finalized UBS credit-profile decisions back to LP Master after a Shadow BB
     * run is accepted.  Called from {@code SubmissionController /complete}.
     *
     * For each LP record in the facility: if an LP Master row exists, update its UBS
     * classification / advance rate / concentration limit from the accepted facility record.
     * If no LP Master row exists yet (LP was new in this submission), create one now so
     * future submissions across any facility benefit from this cycle's decisions.
     * The {@code lp_master_id} FK is stamped onto the facility record after upsert.
     */
    @Transactional
    public void writeBackToLpMaster(int facilityId) {
        for (Lp lp : lpRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId)) {
            LpMaster master = lpMasterRepo.findByInvestorName(lp.getInvestorName())
                .orElseGet(() -> {
                    LpMaster m = new LpMaster();
                    m.setInvestorName(lp.getInvestorName());
                    return m;
                });

            // UBS credit profile — the definitive output of the accepted Shadow BB cycle
            if (lp.getCls()     != null && !lp.getCls().isBlank())     master.setUbsClassification(lp.getCls());
            if (lp.getUbsRate() != null && !lp.getUbsRate().isBlank()) master.setUbsDefaultAdvRate(lp.getUbsRate());
            if (lp.getUbsConc() != null && !lp.getUbsConc().isBlank()) master.setUbsDefaultConcLimit(lp.getUbsConc());

            // Stable identity — refresh blanks with anything the facility record now carries
            if (lp.getInvestorType() != null && !lp.getInvestorType().isBlank() && (master.getInvestorType() == null || master.getInvestorType().isBlank())) master.setInvestorType(lp.getInvestorType());
            if (lp.getInstVsHnw()  != null && !lp.getInstVsHnw().isBlank()  && (master.getInstVsHnw()  == null || master.getInstVsHnw().isBlank()))  master.setInstVsHnw(lp.getInstVsHnw());
            if (lp.getRegion()  != null && !lp.getRegion().isBlank()  && (master.getRegion()  == null || master.getRegion().isBlank()))  master.setRegion(lp.getRegion());
            if (lp.getParent()  != null && !lp.getParent().isBlank()  && (master.getParent()  == null || master.getParent().isBlank()))  master.setParent(lp.getParent());
            master.setSpv(lp.isSpv());
            master.setHighQty(lp.isHighQty());
            master.setIg(lp.isIg());

            // Ratings — overwrite with latest cycle values when non-blank
            if (!lp.getSp().isBlank())    master.setSp(lp.getSp());
            if (!lp.getMdy().isBlank())   master.setMdy(lp.getMdy());
            if (!lp.getFitch().isBlank()) master.setFitch(lp.getFitch());

            // Financial scale — overwrite with latest cycle values when non-null
            if (lp.getAum()          != null) master.setAum(lp.getAum());
            if (lp.getNav()          != null) master.setNav(lp.getNav());
            if (lp.getPension()      != null) master.setPension(lp.getPension());
            if (lp.getPensionFunded()!= null) master.setPensionFunded(lp.getPensionFunded());

            LpMaster saved = lpMasterRepo.save(master);

            // Stamp the FK so future lookups can join directly
            if (!saved.getId().equals(lp.getLpMasterId())) {
                lp.setLpMasterId(saved.getId());
                lpRepo.save(lp);
            }
        }
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
        String notes    = strValueIfValid(row.notes());

        if (sp       != null) { lp.setSp(sp);               changed.add("sp"); }
        if (mdy      != null) { lp.setMdy(mdy);             changed.add("mdy"); }
        if (fitch    != null) { lp.setFitch(fitch);         changed.add("fitch"); }
        if (nav      != null) { lp.setNav(nav);             changed.add("nav"); }
        if (agentCls != null) { lp.setAgentCls(agentCls);   changed.add("agentCls"); }
        if (parent   != null) { lp.setParent(parent);       changed.add("parent"); }
        if (notes    != null) { lp.setNotes(notes);         changed.add("notes"); }

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
