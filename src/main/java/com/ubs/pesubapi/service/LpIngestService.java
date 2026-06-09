package com.ubs.pesubapi.service;

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
        List<Lp> facilityLps = lpRepo.findByFacilityIdOrderByRankAsc(request.facilityId());
        List<String> names   = facilityLps.stream().map(Lp::getName).toList();
        Map<String, Lp> byName = facilityLps.stream()
            .collect(Collectors.toMap(Lp::getName, lp -> lp, (a, b) -> a));

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
            MatchingService.MatchCandidate best = matchingService.matchBestInList(extractedName, names);

            if (best == null || "Reject".equals(best.action())) {
                skipped++;
                int score = best != null ? best.score() : 0;
                results.add(result(row.rowIndex(), extractedName, null, null, score,
                    "Skipped", List.of(), List.of("No LP match found in facility (score: " + score + ")")));
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
                results.add(result(row.rowIndex(), extractedName, lp.getId(), lp.getName(),
                    best.score(), "Queued", List.of(), reasons));
                if (submissionId > 0) {
                    persistQueueEntry(submissionId, request.facilityId(), row.rowIndex(),
                        extractedName, lp.getId(), lp.getName(), best.score(), reasons);
                }
            } else {
                List<String> updatedFields = applyFields(lp, row);
                lpRepo.save(lp);
                updated++;
                results.add(result(row.rowIndex(), extractedName, lp.getId(), lp.getName(),
                    best.score(), "Updated", updatedFields, List.of()));
            }
        }

        String fmt = request.extraction().template() != null
            ? request.extraction().template().format() : "UNKNOWN";
        return new IngestResult(request.facilityId(), LocalDateTime.now(),
            fmt, results, updated, queued, skipped);
    }

    private void persistQueueEntry(int submissionId, int facilityId, int rowIndex,
                                   String extractedName, Integer matchedLpId,
                                   String matchedLpName, int matchScore,
                                   List<String> reasons) {
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
        matchQueueRepo.save(entry);
    }

    private List<String> applyFields(Lp lp, IngestRequest.ExtractedLpRow row) {
        List<String> changed = new ArrayList<>();
        BigDecimal aum   = valueIfValid(row.aum());
        BigDecimal comm  = valueIfValid(row.commitment());
        BigDecimal uc    = valueIfValid(row.uncalled());
        BigDecimal rate  = valueIfValid(row.agentRate());
        BigDecimal conc  = valueIfValid(row.concentrationLimit());

        if (aum  != null) { lp.setAum(formatMoney(aum));        changed.add("aum"); }
        if (comm != null) { lp.setCapCommit(formatMoney(comm));  changed.add("capCommit"); }
        if (uc   != null) { lp.setUc(formatMoney(uc));           changed.add("uc"); }
        if (rate != null) { lp.setAgentRate(formatRate(rate));   changed.add("agentRate"); }
        if (conc != null) { lp.setAgentConc(formatRate(conc));   changed.add("agentConc"); }

        lp.setUpdatedAt(LocalDateTime.now());
        return changed;
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
