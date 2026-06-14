package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.entity.MatchQueueEntry;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.service.MatchingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/matching")
public class MatchingController {

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
        return ResponseEntity.ok(matchingService.test(name));
    }

    // ── GET /api/matching/queue?submissionId= ─────────────────────────────────

    @GetMapping("/queue")
    public ResponseEntity<List<MatchQueueItemDto>> queue(
            @RequestParam(required = false) Integer submissionId) {

        List<MatchQueueEntry> entries = submissionId != null
            ? matchQueueRepo.findBySubmissionIdOrderByRowIndexAsc(submissionId)
            : matchQueueRepo.findAll();

        Map<Integer, String> facilityNames = new java.util.HashMap<>();
        entries.stream().map(MatchQueueEntry::getFacilityId)
            .filter(Objects::nonNull).distinct()
            .forEach(fid -> {
                Integer nonNullFacilityId = Objects.requireNonNull(fid);
                facilityRepo.findById(nonNullFacilityId)
                    .ifPresent(f -> facilityNames.put(nonNullFacilityId, f.getName()));
            });

        List<MatchQueueItemDto> dtos = entries.stream()
            .map(e -> toDto(e, facilityNames.getOrDefault(e.getFacilityId(), "—")))
            .toList();

        return ResponseEntity.ok(dtos);
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

            String facilityName = java.util.Optional.ofNullable(saved.getFacilityId())
                .flatMap(fid -> facilityRepo.findById(Objects.requireNonNull(fid)))
                .map(f -> f.getName()).orElse("—");
            return ResponseEntity.ok(toDto(saved, facilityName));
        }).orElse(ResponseEntity.notFound().build());
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
        com.fasterxml.jackson.databind.JsonNode matchDetails
    ) {}

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
