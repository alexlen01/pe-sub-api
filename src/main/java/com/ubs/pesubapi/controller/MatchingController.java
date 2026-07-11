package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.service.MatchingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/matching")
public class MatchingController {

    private static final Logger log = LoggerFactory.getLogger(MatchingController.class);

    private final MatchingService            matchingService;
    private final MatchQueueEntryRepository  matchQueueRepo;
    private final FacilityRepository         facilityRepo;

    public MatchingController(MatchingService matchingService,
                               MatchQueueEntryRepository matchQueueRepo,
                               FacilityRepository facilityRepo) {
        this.matchingService = matchingService;
        this.matchQueueRepo  = matchQueueRepo;
        this.facilityRepo    = facilityRepo;
    }

    // ── POST /api/matching/test ───────────────────────────────────────────────

    @PostMapping("/test")
    public ResponseEntity<MatchingService.MatchTestResult> test(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().build();
        MatchingService.MatchTestResult result = matchingService.test(name);
        MatchingService.MatchCandidate best = result.matches().isEmpty() ? null : result.matches().getFirst();
        log.info("Matching test name='{}' bestMatch='{}' score={}",
            name, best != null ? best.name() : null, best != null ? best.score() : null);
        return ResponseEntity.ok(result);
    }

    // ── GET /api/matching/queue?submissionId= ─────────────────────────────────

    @GetMapping("/queue")
    public ResponseEntity<List<MatchQueueItemDto>> queue(
            @RequestParam(required = false) Integer submissionId) {

        List<MatchQueueEntry> entries = submissionId != null
            ? matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId)
            : matchQueueRepo.findAll();

        Map<Integer, String> facilityNames = new java.util.HashMap<>();
        entries.stream().map(entry -> entry.getFacilityId())
            .filter(Objects::nonNull).distinct()
            .forEach(fid -> {
                Integer nonNullFacilityId = Objects.requireNonNull(fid);
                facilityRepo.findById(nonNullFacilityId)
                    .ifPresent(f -> facilityNames.put(nonNullFacilityId, f.getName()));
            });

        List<MatchQueueItemDto> dtos = entries.stream()
            .map(e -> toDto(e, facilityNames.getOrDefault(e.getFacilityId(), "—")))
            .toList();

        log.info("Match queue listed submissionId={} count={}", submissionId, dtos.size());
        return ResponseEntity.ok(dtos);
    }

    // ── DELETE /api/matching/queue/:id ───────────────────────────────────────

    @DeleteMapping("/queue/{id}")
    public ResponseEntity<Void> discard(@PathVariable int id) {
        if (!matchQueueRepo.existsById(id)) return ResponseEntity.notFound().build();
        matchQueueRepo.deleteById(id);
        log.info("Match queue entry discarded id={}", id);
        return ResponseEntity.noContent().build();
    }

    // ── PATCH /api/matching/queue/:id ─────────────────────────────────────────

    @PatchMapping("/queue/{id}")
    public ResponseEntity<MatchQueueItemDto> decide(
            @PathVariable int id,
            @RequestBody Map<String, Object> body) {

        return matchQueueRepo.findById(id).map(entry -> {
            if (body.containsKey("decision")) {
                entry.setDecision(capitalise((String) body.get("decision")));
            }
            if (body.containsKey("masterName") && body.get("masterName") instanceof String mn) {
                entry.setMasterNameOverride(mn.isBlank() ? null : mn);
                if (!mn.isBlank()) entry.setDecision("Accepted");
            }
            MatchQueueEntry saved = matchQueueRepo.save(Objects.requireNonNull(entry));
            log.info("Match queue entry decided id={} submissionId={} extracted='{}' decision='{}' masterOverride='{}'",
                saved.getId(), saved.getSubmissionId(), saved.getExtractedName(),
                saved.getDecision(), saved.getMasterNameOverride());

            String facilityName = java.util.Optional.ofNullable(saved.getFacilityId())
                .flatMap(fid -> facilityRepo.findById(Objects.requireNonNull(fid)))
                .map(f -> f.getName()).orElse("—");
            return ResponseEntity.ok(toDto(saved, facilityName));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /api/matching/queue/decisions (batch) ───────────────────────────
    //
    // Applies many accept/reject decisions in one request + transaction. Collapses the N per-row
    // PATCHes the Match Queue previously fired on a bulk Accept/Reject (browsers cap ~6 concurrent,
    // so a 52-row action ran in ~9 sequential waves) into a single round-trip, cutting the latency
    // the Commit step then has to await. Routing: the literal "decisions" segment is matched ahead
    // of the "{id}" path variable, so this never collides with the single-decide endpoint above.

    @PatchMapping("/queue/decisions")
    public ResponseEntity<List<MatchQueueItemDto>> decideBatch(
            @Valid @RequestBody BatchDecisionRequest request) {

        // Last decision for a given id wins if the client repeats one; preserve request order.
        Map<Integer, BatchDecisionRequest.Decision> byId = request.decisions().stream()
            .collect(Collectors.toMap(d -> Objects.requireNonNull(d).id(), d -> d,
                (a, b) -> b, LinkedHashMap::new));

        List<MatchQueueEntry> entries = matchQueueRepo.findAllById(byId.keySet());
        entries.forEach(entry -> {
            BatchDecisionRequest.Decision d = byId.get(entry.getId());
            entry.setDecision(capitalise(d.decision()));
            if (d.masterName() != null) {
                entry.setMasterNameOverride(d.masterName().isBlank() ? null : d.masterName());
                if (!d.masterName().isBlank()) entry.setDecision("Accepted");
            }
        });
        // saveAll runs in a single transaction — the batch is applied all-or-nothing.
        List<MatchQueueEntry> saved = matchQueueRepo.saveAll(entries);

        Map<Integer, String> facilityNames = new HashMap<>();
        saved.stream().map(entry -> entry.getFacilityId())
            .filter(Objects::nonNull).distinct()
            .forEach(fid -> {
                Integer nonNullFacilityId = Objects.requireNonNull(fid);
                facilityRepo.findById(nonNullFacilityId)
                    .ifPresent(f -> facilityNames.put(nonNullFacilityId, f.getName()));
            });

        List<MatchQueueItemDto> dtos = saved.stream()
            .map(e -> toDto(e, facilityNames.getOrDefault(e.getFacilityId(), "—")))
            .toList();
        log.info("Match queue batch decided requested={} applied={}", byId.size(), dtos.size());
        return ResponseEntity.ok(dtos);
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    record MatchQueueItemDto(
        Integer id,
        Integer submissionId,
        Integer facilityId,
        String facilityName,
        String agentName,
        String masterName,
        Integer score,
        String decision,
        String status,
        boolean isNew,
        List<String> reasons,
        tools.jackson.databind.JsonNode matchDetails
    ) {}

    record BatchDecisionRequest(
        @NotEmpty List<@Valid Decision> decisions
    ) {
        record Decision(
            @NotNull Integer id,
            @NotNull String decision,
            String masterName
        ) {}
    }

    private MatchQueueItemDto toDto(MatchQueueEntry e, String facilityName) {
        String displayName = e.getMasterNameOverride() != null
            ? e.getMasterNameOverride() : e.getMatchedLpName();
        return new MatchQueueItemDto(
            e.getId(), e.getSubmissionId(), e.getFacilityId(), facilityName,
            e.getExtractedName(), displayName,
            e.getMatchScore(), e.getDecision(), e.getDecision(),
            e.isNew(), e.getReasons(), e.getMatchDetails()
        );
    }

    private String capitalise(String s) {
        if (s == null || s.isBlank()) return s;
        String lower = s.toLowerCase();
        return switch (lower) {
            case "accepted" -> "Accepted";
            case "rejected" -> "Rejected";
            case "manual"   -> "Manual";
            default         -> "Pending";
        };
    }
}
