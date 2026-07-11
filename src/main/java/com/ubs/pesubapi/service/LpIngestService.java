package com.ubs.pesubapi.service;

import tools.jackson.databind.JsonNode;
import com.ubs.pesubapi.dto.IngestRequest;
import com.ubs.pesubapi.dto.IngestResult;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.util.AgentLpClassificationDeriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LpIngestService {

    private static final Logger log = LoggerFactory.getLogger(LpIngestService.class);
    private static final double MIN_FIELD_CONFIDENCE = 0.7;

    private final LpRecordRepository              lpRecordRepo;
    private final LpMasterRepository        lpMasterRepo;
    private final MatchingService           matchingService;
    private final MatchQueueEntryRepository matchQueueRepo;

    public LpIngestService(LpRecordRepository lpRecordRepo,
                           LpMasterRepository lpMasterRepo,
                           MatchingService matchingService,
                           MatchQueueEntryRepository matchQueueRepo) {
        this.lpRecordRepo          = lpRecordRepo;
        this.lpMasterRepo    = lpMasterRepo;
        this.matchingService = matchingService;
        this.matchQueueRepo  = matchQueueRepo;
    }

    public IngestResult ingest(int submissionId, IngestRequest request) {
        List<LpRecord> facilityLps = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(request.facilityId());
        List<String> names   = facilityLps.stream().map(lpRecord -> lpRecord.getInvestorName()).toList();
        Map<String, LpRecord> byName = facilityLps.stream()
            .collect(Collectors.toMap(lpRecord -> lpRecord.getInvestorName(), lpRecord -> lpRecord, (a, b) -> a));
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
                    ? List.of("new LP — no matching record found in facility LP Master")
                    : List.of("new LP — best match score " + score + " is below the no-match threshold");
                results.add(result(row.rowIndex(), extractedName, null, null, score,
                    "Queued", List.of(), reasons));
                if (submissionId > 0) {
                    persistQueueEntry(submissionId, request.facilityId(), row.rowIndex(),
                        extractedName, null, null, score, reasons,
                        matchingService.analyzeTree(extractedName, prepared, 5));
                }
                continue;
            }

            LpRecord lpRecord = byName.get(best.name());
            if (lpRecord == null) {
                skipped++;
                results.add(result(row.rowIndex(), extractedName, null, best.name(), best.score(),
                    "Skipped", List.of(), List.of("Internal error: matched LpRecord not found")));
                continue;
            }

            boolean needsReview = row.requiresReview() || !"Accept".equals(best.action());

            if (needsReview) {
                queued++;
                List<String> reasons = reviewReasons(row, best);
                results.add(result(row.rowIndex(), extractedName, lpRecord.getId(), lpRecord.getInvestorName(),
                    best.score(), "Queued", List.of(), reasons));
                if (submissionId > 0) {
                    persistQueueEntry(submissionId, request.facilityId(), row.rowIndex(),
                        extractedName, lpRecord.getId(), lpRecord.getInvestorName(), best.score(), reasons,
                        matchingService.analyzeTree(extractedName, prepared, 5));
                }
            } else {
                if (submissionId > 0) {
                    queued++;
                    results.add(result(row.rowIndex(), extractedName, lpRecord.getId(), lpRecord.getInvestorName(),
                        best.score(), "Queued", List.of(),
                        List.of("Auto-match queued for Match Queue name confirmation")));
                } else {
                    List<String> updatedFields = applyFields(lpRecord, row);
                    lpRecordRepo.save(lpRecord);
                    updated++;
                    results.add(result(row.rowIndex(), extractedName, lpRecord.getId(), lpRecord.getInvestorName(),
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

        // Preserve every extracted row in natural (source-file) order. A *stable* sort on the
        // extraction order key keeps the original array order for rows that share — or lack — a key,
        // so no row is silently dropped. The prior HashMap keyed on this value discarded any rows
        // that collided on it (duplicate id/rowIndex, or both missing → key -1).
        List<JsonNode> orderedRows = new ArrayList<>();
        extractedLps.forEach(orderedRows::add);
        orderedRows.sort(Comparator.comparingInt(this::extractionOrderKey));

        Map<Integer, MatchQueueEntry> queueByRow =
            matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId).stream()
                .collect(Collectors.toMap(e -> e.getRowIndex(), e -> e, (a, b) -> a, HashMap::new));

        // Replace all existing facility LP records — this submission is authoritative.
        matchQueueRepo.clearMatchedLpIdsForFacility(facilityId);
        lpRecordRepo.deleteByFacilityId(facilityId);
        lpRecordRepo.flush();

        // One LpRecord per extracted line. Same-name lines (an LP across multiple fund sleeves, or
        // two lines both accepted against one LP Master entry) are kept as DISTINCT records —
        // collapsing them by investor name would drop a line's uncalled capital and understate the
        // borrowing base. This is why V1_4 removed the (facility_id, investor_name) unique constraint.
        List<LpRecord> toSave = new ArrayList<>();
        int skippedBlank = 0;

        // Insert every extracted row; no row is discarded regardless of match queue status.
        for (JsonNode row : orderedRows) {
            int rowIndex = extractionOrderKey(row);
            String extractedName = row.path("name").asString("").trim();
            if (extractedName.isBlank()) { skippedBlank++; continue; }

            MatchQueueEntry entry = queueByRow.get(rowIndex);
            boolean isAccepted = entry != null
                && "Accepted".equals(entry.getDecision())
                && !entry.isNew();
            String name = isAccepted ? lpNameForCommit(entry, false) : extractedName;
            if (name == null || name.isBlank()) name = extractedName;

            LpRecord lpRecord = new LpRecord();
            lpRecord.setFacilityId(facilityId);
            lpRecord.setInvestorName(name);
            lpRecord.setInvestorType("");
            lpRecord.setInstVsHnw("Institutional");
            lpRecord.setRegion("US");
            lpRecord.setCls("Eligible");

            // For accepted LP Master matches: pre-populate empty fields from LP Master
            // (stable identity, ratings, UBS credit profile) before extraction fields win.
            if (isAccepted) {
                final LpRecord lpRef = lpRecord;
                lpMasterRepo.findByInvestorName(name).ifPresent(m -> applyLpMasterBaseline(lpRef, m));
            }

            lpRecord.setSourceSeq(rowIndex);
            applyExtractedJsonRow(lpRecord, row);
            toSave.add(lpRecord);
        }

        lpRecordRepo.saveAll(toSave);

        // Reconciliation: extractedRows == persisted + skippedBlank. Any shortfall is now a real,
        // logged fact rather than a silent name-collapse (the "52 processed, 47 persisted" bug).
        log.info("Commit decisions submission={} facility={}: extractedRows={} persisted={} skippedBlank={}",
            submissionId, facilityId, orderedRows.size(), toSave.size(), skippedBlank);
    }

    private int extractionOrderKey(JsonNode row) {
        return row.path("id").asInt(row.path("rowIndex").asInt(-1));
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

    private void applyExtractedJsonRow(LpRecord lpRecord, JsonNode row) {
        String aum     = textOrNull(row, "aum");
        String commit  = textOrNull(row, "commit");
        String uncalled = textOrNull(row, "uncalled");
        String rate    = textOrNull(row, "agentRate");
        String conc    = textOrNull(row, "agentConc");
        String parent  = textOrNull(row, "parent");
        String fundSleeve = textOrNull(row, "fundSleeve");
        String nav     = textOrNull(row, "nav");
        String sp      = textOrNull(row, "sp");
        String mdy     = textOrNull(row, "moodys");
        String fitch   = textOrNull(row, "fitch");
        String investorType = extractedText(row, "investorType", "Investor Type");
        String notes   = textOrNull(row, "notes");
        // Agent LP Category verbatim from the Agent BB (e.g. "Pension Fund", "Designated PWM",
        // "Rated Included"). Persisted here so it survives the Commit Decisions step — the bb.run and
        // classification-edit paths already set it; this path previously dropped it, leaving the agent
        // value blank in Shadow BB. It is distinct from Investor Type, a manual field.
        String agentCls  = extractedText(row, "agentClass", "LP Category");
        String agentClsSource = agentCls != null
            ? normalizeAgentClsSource(textOrNull(row, "agentClsSource"))
            : null;
        if (agentCls != null && agentClsSource == null) agentClsSource = "EXTRACTED";
        String calledCap = extractedText(row, "calledCap", "Called Capital");
        String pctCalled = textOrNull(row, "pctCalled");
        String pctUncalled = textOrNull(row, "pctUncalled");
        // Derived-or-extracted commitment/concentration figures: the direct row key is written
        // by current extractions; the canonicalFields fallback covers rows stored before the
        // key existed. Values may be platform-derived (pe-sub-extraction DerivedFieldCalculator)
        // when the agent workbook has no such column.
        String pctCapCommit    = extractedText(row, "pctCapCommit", "% of Capital Commitments");
        String agentExcessConc = extractedText(row, "agentExcessConc", "Excess Concentration");
        // Prefer the raw extracted agent BB column; fall back to the uncalled×rate proxy.
        String agentBB   = textOrNull(row, "agentBB");
        if (agentBB == null) agentBB = textOrNull(row, "agentBBFmt");

        if (aum      != null) lpRecord.setAum(aum);
        if (commit   != null) lpRecord.setCapCommit(commit);
        if (uncalled != null) lpRecord.setUc(uncalled);
        if (rate     != null) lpRecord.setAgentRate(rate);
        if (conc     != null) lpRecord.setAgentConc(conc);
        if (parent   != null) lpRecord.setParent(parent);
        if (fundSleeve != null) lpRecord.setFundSleeve(fundSleeve);
        if (nav      != null) lpRecord.setNav(nav);
        if (sp       != null) lpRecord.setSp(sp);
        if (mdy      != null) lpRecord.setMdy(mdy);
        if (fitch    != null) lpRecord.setFitch(fitch);
        if (investorType != null) lpRecord.setInvestorType(investorType);
        if (agentCls != null) {
            lpRecord.setAgentCls(agentCls);
            lpRecord.setAgentClsSource(agentClsSource);
        } else {
            // Agent BB carried no LP Category column for this row: derive it from the investor
            // type and any ratings so Shadow BB has a category to work with, marking the source
            // DERIVED so the UI can distinguish it from an extracted or user-edited value.
            String derivedAgentCls = AgentLpClassificationDeriver.derive(
                lpRecord.getInvestorType(),
                lpRecord.getSp(), lpRecord.getMdy(), lpRecord.getFitch(),
                null, null,
                notes != null ? notes : lpRecord.getNotes(),
                false);
            if (derivedAgentCls != null) {
                lpRecord.setAgentCls(derivedAgentCls);
                lpRecord.setAgentClsSource("DERIVED");
            }
        }
        if (agentBB  != null) lpRecord.setAbb(agentBB);
        if (calledCap  != null) lpRecord.setCalledCap(calledCap);
        if (pctCalled  != null) lpRecord.setPctCalled(pctCalled);
        if (pctUncalled != null) lpRecord.setPctUncalled(pctUncalled);
        if (pctCapCommit    != null) lpRecord.setPctCapCommit(pctCapCommit);
        if (agentExcessConc != null) lpRecord.setAgentExcessConc(agentExcessConc);
        if (notes     != null) lpRecord.setNotes(notes);
        lpRecord.setTf(isTransferee(row));

        // Precise numeric money (C2): prefer the exact value carried in the stored JSON; fall back
        // to parsing the display string for extractions saved before the numeric fields existed.
        BigDecimal ucNum   = moneyDollars(row, "uncalledNum", "uncalled");
        BigDecimal commNum  = moneyDollars(row, "commitNum",  "commit");
        BigDecimal aumNum  = moneyDollars(row, "aumNum",     "aum");
        BigDecimal abbNum  = moneyDollars(row, "agentBBNum", "agentBB");
        if (ucNum   != null) lpRecord.setUcNum(ucNum);
        if (commNum != null) lpRecord.setCapCommitNum(commNum);
        if (aumNum  != null) lpRecord.setAumNum(aumNum);
        if (abbNum  != null) lpRecord.setAbbNum(abbNum);

        lpRecord.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Precise money in absolute dollars: the numeric field from the stored extraction JSON when
     * present, else the display string parsed back to dollars. Returns null when neither yields a
     * usable value, letting the engine fall through to its own string parse.
     */
    private BigDecimal moneyDollars(JsonNode row, String numericKey, String displayKey) {
        JsonNode num = row.get(numericKey);
        if (num != null && num.isNumber()) return num.decimalValue();
        if (num != null && num.isString() && !num.asString().isBlank()) {
            try { return new BigDecimal(num.asString().trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        String display = textOrNull(row, displayKey);
        if (display == null) return null;
        double millions = BbCalculationService.parseMoney(display);
        return millions == 0 ? null : BigDecimal.valueOf(millions * 1_000_000.0);
    }

    private boolean isTransferee(JsonNode row) {
        JsonNode direct = row.get("tf");
        if (direct != null && direct.isBoolean()) return direct.asBoolean();
        direct = row.get("transferee");
        if (direct == null || direct.isNull()) return false;
        if (direct.isBoolean()) return direct.asBoolean();
        String value = direct.asString("").trim();
        return !value.isBlank()
            && !"false".equalsIgnoreCase(value)
            && !"no".equalsIgnoreCase(value)
            && !"n".equalsIgnoreCase(value)
            && !"0".equals(value);
    }

    private String normalizeAgentClsSource(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "EXTRACTED", "DERIVED", "USER_EDITED" -> value;
            default -> null;
        };
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
    private void applyLpMasterBaseline(LpRecord lpRecord, LpMaster master) {
        // Stable identity — override defaults set at new-record creation time
        if (master.getInvestorType() != null && !master.getInvestorType().isBlank()
                && (lpRecord.getInvestorType() == null || lpRecord.getInvestorType().isBlank())) {
            lpRecord.setInvestorType(master.getInvestorType());
        }
        if (master.getInstVsHnw() != null && !master.getInstVsHnw().isBlank()
                && (lpRecord.getInstVsHnw() == null || lpRecord.getInstVsHnw().isBlank())) {
            lpRecord.setInstVsHnw(master.getInstVsHnw());
        }
        if (master.getRegion() != null && !master.getRegion().isBlank()
                && "US".equals(lpRecord.getRegion())) {
            lpRecord.setRegion(master.getRegion());
        }
        lpRecord.setSpv(master.isSpv());
        lpRecord.setHighQty(master.isHighQty());
        lpRecord.setIg(master.isIg());
        if (master.getParent() != null && !master.getParent().isBlank() && lpRecord.getParent() == null) {
            lpRecord.setParent(master.getParent());
        }

        // Ratings — apply when LP Master has a value and the facility record is still blank
        if (!master.getSp().isBlank()    && lpRecord.getSp().isBlank())    lpRecord.setSp(master.getSp());
        if (!master.getMdy().isBlank()   && lpRecord.getMdy().isBlank())   lpRecord.setMdy(master.getMdy());
        if (!master.getFitch().isBlank() && lpRecord.getFitch().isBlank()) lpRecord.setFitch(master.getFitch());

        // Financial scale — fill nulls from LP Master
        if (master.getAum()          != null && lpRecord.getAum()          == null) lpRecord.setAum(master.getAum());
        if (master.getNav()          != null && lpRecord.getNav()          == null) lpRecord.setNav(master.getNav());
        if (master.getPension()      != null && lpRecord.getPension()      == null) lpRecord.setPension(master.getPension());
        if (master.getPensionFunded()!= null && lpRecord.getPensionFunded()== null) lpRecord.setPensionFunded(master.getPensionFunded());

        // UBS credit profile — always apply; these fields are never set by extraction so
        // LP Master is the sole pre-populated source ahead of the credit officer's review
        if (master.getUbsClassification() != null && !master.getUbsClassification().isBlank()) {
            lpRecord.setCls(master.getUbsClassification());
        }
        if (master.getUbsDefaultAdvRate() != null && !master.getUbsDefaultAdvRate().isBlank()) {
            lpRecord.setUbsRate(master.getUbsDefaultAdvRate());
        }
        if (master.getUbsDefaultConcLimit() != null && !master.getUbsDefaultConcLimit().isBlank()) {
            lpRecord.setUbsConc(master.getUbsDefaultConcLimit());
        }
    }

    private String textOrNull(JsonNode node, String field) {
        String v = node.path(field).asString(null);
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }

    private String extractedText(JsonNode row, String directField, String canonicalField) {
        String direct = textOrNull(row, directField);
        if (direct != null) return direct;
        JsonNode canonicals = row.path("canonicalFields");
        if (!canonicals.isObject()) return null;
        return textOrNull(canonicals, canonicalField);
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

    private List<String> applyFields(LpRecord lpRecord, IngestRequest.ExtractedLpRow row) {
        List<String> changed = new ArrayList<>();
        BigDecimal aum  = valueIfValid(row.aum());
        BigDecimal comm = valueIfValid(row.commitment());
        BigDecimal uc   = valueIfValid(row.uncalled());
        BigDecimal rate = valueIfValid(row.agentRate());
        BigDecimal conc = valueIfValid(row.concentrationLimit());

        // Dual-write: keep the formatted string for display and store the precise value (C2) so
        // the BB engine reads exact dollars instead of re-parsing a rounded "$12.3M".
        if (aum  != null) { lpRecord.setAum(formatMoney(aum));  lpRecord.setAumNum(aum);       changed.add("aum"); }
        if (comm != null) { lpRecord.setCapCommit(formatMoney(comm)); lpRecord.setCapCommitNum(comm); changed.add("capCommit"); }
        if (uc   != null) { lpRecord.setUc(formatMoney(uc));    lpRecord.setUcNum(uc);         changed.add("uc"); }
        if (rate != null) { lpRecord.setAgentRate(formatRate(rate));   changed.add("agentRate"); }
        if (conc != null) { lpRecord.setAgentConc(formatRate(conc));   changed.add("agentConc"); }

        String sp       = strValueIfValid(row.sp());
        String mdy      = strValueIfValid(row.mdy());
        String fitch    = strValueIfValid(row.fitch());
        String nav      = strValueIfValid(row.nav());
        String investorType = strValueIfValid(row.investorType());
        String agentCls = strValueIfValid(row.agentCls());
        String agentClsSource = normalizeAgentClsSource(strValueIfValid(row.agentClsSource()));
        String parent   = strValueIfValid(row.parent());
        String notes    = strValueIfValid(row.notes());
        String transferee = strValueIfValid(row.transferee());

        if (sp       != null) { lpRecord.setSp(sp);               changed.add("sp"); }
        if (mdy      != null) { lpRecord.setMdy(mdy);             changed.add("mdy"); }
        if (fitch    != null) { lpRecord.setFitch(fitch);         changed.add("fitch"); }
        if (nav      != null) { lpRecord.setNav(nav);             changed.add("nav"); }
        if (investorType != null) { lpRecord.setInvestorType(investorType); changed.add("investorType"); }
        if (agentCls != null) {
            lpRecord.setAgentCls(agentCls);
            lpRecord.setAgentClsSource(agentClsSource != null ? agentClsSource : "USER_EDITED");
            changed.add("agentCls");
        }
        if (parent   != null) { lpRecord.setParent(parent);       changed.add("parent"); }
        if (notes    != null) { lpRecord.setNotes(notes);         changed.add("notes"); }
        if (transferee != null) {
            lpRecord.setTf(isTruthyFlag(transferee));
            changed.add("tf");
        }

        lpRecord.setUpdatedAt(LocalDateTime.now());
        return changed;
    }

    private String strValueIfValid(IngestRequest.StringField f) {
        return (f != null && f.value() != null && !f.value().isBlank()
                && f.confidence() >= MIN_FIELD_CONFIDENCE) ? f.value() : null;
    }

    private boolean isTruthyFlag(String value) {
        String normalized = value.trim();
        return !"false".equalsIgnoreCase(normalized)
            && !"no".equalsIgnoreCase(normalized)
            && !"n".equalsIgnoreCase(normalized)
            && !"0".equals(normalized);
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
