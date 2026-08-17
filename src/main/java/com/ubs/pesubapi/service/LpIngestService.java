package com.ubs.pesubapi.service;

import tools.jackson.databind.JsonNode;
import com.ubs.pesubapi.dto.IngestRequest;
import com.ubs.pesubapi.dto.IngestResult;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.util.AgentLpClassificationDeriver;
import com.ubs.pesubapi.util.MoneyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    private final LpRecordRepository              lpRecordRepo;
    private final LpMasterResolutionService resolutionService;
    private final LpAliasService            aliasService;
    private final MatchingService           matchingService;
    private final MatchQueueEntryRepository matchQueueRepo;

    /** Minimum per-field extraction confidence before a value is written to an LP record. */
    private final double minFieldConfidence;

    public LpIngestService(LpRecordRepository lpRecordRepo,
                           LpMasterResolutionService resolutionService,
                           LpAliasService aliasService,
                           MatchingService matchingService,
                           MatchQueueEntryRepository matchQueueRepo,
                           @Value("${app.ingest.min-field-confidence}") double minFieldConfidence) {
        this.lpRecordRepo          = lpRecordRepo;
        this.resolutionService = resolutionService;
        this.aliasService      = aliasService;
        this.matchingService = matchingService;
        this.matchQueueRepo  = matchQueueRepo;
        this.minFieldConfidence = minFieldConfidence;
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
        // Queue entries whose resolved match id / ultimate parent were filled in below. The bulk
        // clearMatchedLpIdsForFacility above ran as JPQL, so these are re-saved explicitly rather
        // than left to dirty-checking against a persistence context that update bypassed.
        List<MatchQueueEntry> resolvedEntries = new ArrayList<>();
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
            lpRecord.setInstitutionalOrHnw("Institutional");
            // Missing source geography must remain missing. Do not synthesize a US domicile;
            // an accepted LP Master match or an extracted Region / Location may populate it.
            lpRecord.setRegionLocation("");
            lpRecord.setUbsLpCategory("Eligible");

            // For accepted LP Master matches: pre-populate empty fields from LP Master
            // (stable identity, ratings, UBS credit profile) before extraction fields win.
            // A matched child/feeder routes up its parent chain — see LpMasterResolutionService.
            if (isAccepted && entry != null) {
                var resolved = resolutionService.resolveByName(name).orElse(null);
                if (resolved != null) {
                    applyLpMasterBaseline(lpRecord, resolved);
                    // The matched record — child, not parent — is the identity of record, so the
                    // audit trail keeps naming the entity the agent listed.
                    lpRecord.setLpMasterId(resolved.matched().getId());
                    entry.setMatchedLpMasterId(resolved.matched().getId());
                    entry.setMasterParent(resolved.routed()
                        ? resolved.ultimateParent().getInvestorName() : null);
                    resolvedEntries.add(entry);
                    // Phase 5: the accepted agent string becomes an exact match next upload.
                    aliasService.remember(entry.getExtractedName(), resolved.matched().getId());
                }
            }

            lpRecord.setSourceSeq(rowIndex);
            applyExtractedJsonRow(lpRecord, row);
            // DEBUG carries the full extracted row so a per-record persistence failure
            // (e.g. "value too long for character varying(N)") is attributable to a specific
            // LP in lower environments; INFO+ (higher environments) logs nothing per record.
            log.debug("Committing LP record: facility={} rowIndex={} name='{}' accepted={} extractedRow={}",
                facilityId, rowIndex, name, isAccepted, row);
            toSave.add(lpRecord);
        }

        lpRecordRepo.saveAll(toSave);
        if (!resolvedEntries.isEmpty()) matchQueueRepo.saveAll(resolvedEntries);

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
        String rate    = textOrNull(row, "agentRate");
        String conc    = textOrNull(row, "agentConc");
        String parent  = textOrNull(row, "parent");
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

        // AUM is a VARCHAR display field: keep the agent's reported text verbatim, falling back to
        // the formatted numeric for rows that only carry the precise figure.
        String aumText = textOrNull(row, "aum");
        if (aumText == null) aumText = MoneyValues.display(moneyDollars(row, "aumNum", "aum"));

        // Precise numeric money (C2): extract exact dollar amounts from JSON or parse display strings
        BigDecimal commNum     = moneyDollars(row, "commitNum",  "commit");
        BigDecimal ucNum       = moneyDollars(row, "uncalledNum", "uncalled");
        BigDecimal abbNum      = moneyDollars(row, "agentBBNum", "agentBB");

        if (aumText     != null) lpRecord.setAum(aumText);
        if (commNum     != null) lpRecord.setCapitalCommitment(commNum);
        if (ucNum       != null) lpRecord.setUncalledCapital(ucNum);
        if (rate        != null) lpRecord.setAgentAdvanceRate(MoneyValues.fraction(rate));
        if (conc        != null) lpRecord.setAgentConcentrationLimit(MoneyValues.decimal(conc));
        if (parent      != null) lpRecord.setParent(parent);
        if (nav         != null) lpRecord.setNav(nav);
        if (sp       != null) lpRecord.setSpRating(sp);
        if (mdy      != null) lpRecord.setMoodysRating(mdy);
        if (fitch    != null) lpRecord.setFitchRating(fitch);
        if (investorType != null) lpRecord.setInvestorType(investorType);
        if (agentCls != null) {
            lpRecord.setAgentLpCategory(agentCls);
            lpRecord.setAgentLpCategorySource(agentClsSource);
        } else {
            // Agent BB carried no LP Category column for this row: derive it from the investor
            // type and any ratings so Shadow BB has a category to work with, marking the source
            // DERIVED so the UI can distinguish it from an extracted or user-edited value.
            String derivedAgentCls = AgentLpClassificationDeriver.derive(
                lpRecord.getInvestorType(),
                lpRecord.getSpRating(), lpRecord.getMoodysRating(), lpRecord.getFitchRating(),
                null, null,
                notes != null ? notes : lpRecord.getNotes(),
                false);
            if (derivedAgentCls != null) {
                lpRecord.setAgentLpCategory(derivedAgentCls);
                lpRecord.setAgentLpCategorySource("DERIVED");
            }
        }
        if (abbNum   != null) lpRecord.setAgentBorrowingBase(abbNum);
        if (calledCap  != null) lpRecord.setCalledCapital(MoneyValues.dollars(calledCap));
        if (pctCalled  != null) lpRecord.setPctLpCalled(MoneyValues.fraction(pctCalled));
        if (pctUncalled != null) lpRecord.setPctOfFundUncalled(MoneyValues.fraction(pctUncalled));
        if (pctCapCommit    != null) lpRecord.setPctOfFundCommitments(MoneyValues.fraction(pctCapCommit));
        if (agentExcessConc != null) lpRecord.setAgentExcessConcentration(MoneyValues.dollars(agentExcessConc));
        if (notes     != null) lpRecord.setNotes(notes);
        lpRecord.setTransferee(isTransferee(row));

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
     * Seed a new facility LP record from an accepted LP Master match.
     *
     * <p>Every field resolves child-first: the matched record's own value wins, and only where it is
     * absent does an ancestor supply one ({@link LpMasterResolutionService.Resolution#text} and
     * {@code value} walk the chain). Because credit standing sits at the sponsor level, a feeder
     * with no rating of its own inherits the parent's — which is the whole point of routing.
     *
     * <p>SPV status is read from the matched record directly, not the chain: it describes the entity
     * the agent listed, and a sponsor being an operating company says nothing about its feeder.
     */
    private void applyLpMasterBaseline(LpRecord lpRecord, LpMasterResolutionService.Resolution r) {
        LpMaster matched = r.matched();

        // Stable identity — override defaults set at new-record creation time
        if (lpRecord.getInvestorType() == null || lpRecord.getInvestorType().isBlank()) {
            r.text(m -> m.getInvestorType()).ifPresent(lpRecord::setInvestorType);
        }
        if (lpRecord.getInstitutionalOrHnw() == null || lpRecord.getInstitutionalOrHnw().isBlank()) {
            r.text(m -> m.getInstitutionalOrHnw()).ifPresent(lpRecord::setInstitutionalOrHnw);
        }
        if (lpRecord.getRegionLocation() == null || lpRecord.getRegionLocation().isBlank()) {
            r.text(m -> m.getRegionLocation()).ifPresent(lpRecord::setRegionLocation);
        }
        lpRecord.setSpv(matched.isSpv());
        lpRecord.setHighQuality(r.flag(m -> m.isHighQuality()));
        lpRecord.setInvestmentGrade(r.flag(m -> m.isInvestmentGrade()));
        // Prefer the resolved ultimate parent's name over the matched row's own parent string —
        // it is the entity whose credit profile the record now carries.
        if (lpRecord.getParent() == null) {
            if (r.routed()) lpRecord.setParent(r.ultimateParent().getInvestorName());
            else r.text(m -> m.getParent()).ifPresent(lpRecord::setParent);
        }

        // Ratings — apply when the chain has a value and the facility record is still blank
        if (lpRecord.getSpRating().isBlank())     r.text(m -> m.getSpRating()).ifPresent(lpRecord::setSpRating);
        if (lpRecord.getMoodysRating().isBlank()) r.text(m -> m.getMoodysRating()).ifPresent(lpRecord::setMoodysRating);
        if (lpRecord.getFitchRating().isBlank())  r.text(m -> m.getFitchRating()).ifPresent(lpRecord::setFitchRating);

        // Financial scale — fill nulls from the chain. These are VARCHAR on both sides, so the
        // copy is a plain assignment.
        if (lpRecord.getAum()           == null) r.value(m -> m.getAum()).ifPresent(lpRecord::setAum);
        if (lpRecord.getNav()           == null) r.value(m -> m.getNav()).ifPresent(lpRecord::setNav);
        if (lpRecord.getPensionAssets() == null) r.value(m -> m.getPensionAssets()).ifPresent(lpRecord::setPensionAssets);
        if (lpRecord.getFundingRatio()  == null) r.value(m -> m.getFundingRatio()).ifPresent(lpRecord::setFundingRatio);

        // UBS credit profile — always apply; these fields are never set by extraction so
        // LP Master is the sole pre-populated source ahead of the credit officer's review
        r.text(m -> m.getUbsLpCategory()).ifPresent(lpRecord::setUbsLpCategory);
        r.value(m -> m.getUbsDefaultAdvanceRate()).ifPresent(lpRecord::setUbsAdvanceRate);
        r.value(m -> m.getUbsDefaultConcentrationLimit()).ifPresent(lpRecord::setUbsConcentrationLimit);
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

        // Store precise numeric values
        if (aum  != null) { lpRecord.setAum(MoneyValues.shortDisplay(aum)); changed.add("aum"); }
        if (comm != null) { lpRecord.setCapitalCommitment(comm); changed.add("capCommit"); }
        if (uc   != null) { lpRecord.setUncalledCapital(uc);         changed.add("uc"); }
        if (rate != null) { lpRecord.setAgentAdvanceRate(MoneyValues.fraction(rate));   changed.add("agentRate"); }
        if (conc != null) { lpRecord.setAgentConcentrationLimit(conc);   changed.add("agentConcLimit"); }

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

        if (sp       != null) { lpRecord.setSpRating(sp);               changed.add("sp"); }
        if (mdy      != null) { lpRecord.setMoodysRating(mdy);             changed.add("mdy"); }
        if (fitch    != null) { lpRecord.setFitchRating(fitch);         changed.add("fitch"); }
        if (nav      != null) { lpRecord.setNav(nav);             changed.add("nav"); }
        if (investorType != null) { lpRecord.setInvestorType(investorType); changed.add("investorType"); }
        if (agentCls != null) {
            lpRecord.setAgentLpCategory(agentCls);
            lpRecord.setAgentLpCategorySource(agentClsSource != null ? agentClsSource : "USER_EDITED");
            changed.add("agentCls");
        }
        if (parent   != null) { lpRecord.setParent(parent);       changed.add("parent"); }
        if (notes    != null) { lpRecord.setNotes(notes);         changed.add("notes"); }
        if (transferee != null) {
            lpRecord.setTransferee(isTruthyFlag(transferee));
            changed.add("tf");
        }

        lpRecord.setUpdatedAt(LocalDateTime.now());
        return changed;
    }

    private String strValueIfValid(IngestRequest.StringField f) {
        return (f != null && f.value() != null && !f.value().isBlank()
                && f.confidence() >= minFieldConfidence) ? f.value() : null;
    }

    private boolean isTruthyFlag(String value) {
        String normalized = value.trim();
        return !"false".equalsIgnoreCase(normalized)
            && !"no".equalsIgnoreCase(normalized)
            && !"n".equalsIgnoreCase(normalized)
            && !"0".equals(normalized);
    }

    private BigDecimal valueIfValid(IngestRequest.DecimalField f) {
        return (f != null && f.value() != null && f.confidence() >= minFieldConfidence)
            ? f.value() : null;
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
