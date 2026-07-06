package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.BbSummary;
import com.ubs.pesubapi.dto.FacilityDto;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.service.FacilityService;
import com.ubs.pesubapi.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private static final Logger log = LoggerFactory.getLogger(FacilityController.class);

    record CreateFacilityRequest(@NotBlank String name, @NotBlank String agentBank) {}

    // Partial update of the editable facility fields entered on the Facility Edit screen.
    // All fields are optional; only non-null values are applied. name/agentBank are blank-guarded
    // (a NOT NULL column must never be cleared); the remaining fields may be set or overwritten.
    record UpdateFacilityRequest(String name, String agentBank,
                                 String accountNumber, BigDecimal loanAmount, LocalDate maturityDate, LocalDate collateralDate,
                                 BigDecimal facilitySize, BigDecimal ubsParticipation) {}

    private final FacilityRepository repo;
    private final LpRecordRepository lpRecordRepo;
    private final BbSnapshotRepository snapshotRepo;
    private final NotificationService notifier;
    private final FacilityService facilityService;

    public FacilityController(FacilityRepository repo, LpRecordRepository lpRecordRepo, BbSnapshotRepository snapshotRepo,
                              NotificationService notifier, FacilityService facilityService) {
        this.repo            = repo;
        this.lpRecordRepo          = lpRecordRepo;
        this.snapshotRepo    = snapshotRepo;
        this.notifier        = notifier;
        this.facilityService = facilityService;
    }

    /** Summary of each facility's most recent Shadow BB snapshot, keyed by facility id. */
    private Map<Integer, BbSummary> latestSummariesByFacility() {
        Map<Integer, BbSummary> byFacility = new HashMap<>();
        for (BbSnapshot s : snapshotRepo.findLatestPerFacility()) {
            if (s.getResult() != null) byFacility.put(s.getFacilityId(), s.getResult().summary());
        }
        return byFacility;
    }

    @GetMapping
    public List<FacilityDto> list() {
        Map<Integer, Integer> lpCounts = new HashMap<>();
        for (Object[] row : lpRecordRepo.countGroupedByFacilityId()) {
            lpCounts.put((Integer) row[0], ((Long) row[1]).intValue());
        }
        Map<Integer, BbSummary> summaries = latestSummariesByFacility();
        List<FacilityDto> result = repo.findAll(Sort.by("name")).stream()
            .map(f -> FacilityDto.from(f, lpCounts.getOrDefault(f.getId(), 0), summaries.get(f.getId())))
            .toList();
        log.info("Facilities listed count={}", result.size());
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<FacilityDto> get(@PathVariable int id) {
        BbSummary summary = snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(id)
            .map(s -> s.getResult() != null ? s.getResult().summary() : null)
            .orElse(null);
        return repo.findById(id)
            .map(f -> FacilityDto.from(f, (int) lpRecordRepo.countByFacilityId(id), summary))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody @Valid CreateFacilityRequest req) {
        if (repo.findByName(req.name()).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "A facility with this name already exists."));
        }
        Facility f = new Facility();
        f.setName(req.name());
        f.setAgentBank(req.agentBank());
        Facility saved = repo.save(f);
        notifier.broadcast("New facility onboarded: " + saved.getName());
        log.info("Facility created id={} name='{}' agentBank='{}'", saved.getId(), saved.getName(), saved.getAgentBank());
        return ResponseEntity.status(201).body(FacilityDto.from(saved));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<FacilityDto> patchStatus(@PathVariable int id,
                                                    @RequestBody Map<String, String> body) {
        return repo.findById(id).map(f -> {
            String newStatus = body.get("status");
            // A facility may only be deactivated while it carries no LP records.
            if ("Inactive".equals(newStatus) && lpRecordRepo.countByFacilityId(id) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot deactivate a facility that has LP records.");
            }
            f.setStatus(newStatus);
            f.setUpdatedAt(LocalDateTime.now());
            Facility saved = repo.save(f);
            notifier.broadcast(f.getName() + " status updated to " + newStatus);
            log.info("Facility status updated id={} name='{}' status='{}'", id, saved.getName(), newStatus);
            return ResponseEntity.ok(FacilityDto.from(saved, (int) lpRecordRepo.countByFacilityId(id)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<FacilityDto> update(@PathVariable int id,
                                              @RequestBody UpdateFacilityRequest req) {
        return repo.findById(id).map(f -> {
            if (req.name() != null && !req.name().isBlank()) {
                String name = req.name().strip();
                repo.findByName(name).ifPresent(other -> {
                    if (!other.getId().equals(f.getId())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "A facility with this name already exists.");
                    }
                });
                f.setName(name);
            }
            if (req.agentBank() != null && !req.agentBank().isBlank()) f.setAgentBank(req.agentBank().strip());
            if (req.accountNumber() != null)    f.setAccountNumber(req.accountNumber());
            if (req.loanAmount() != null)       f.setLoanAmount(req.loanAmount());
            if (req.maturityDate() != null)     f.setMaturityDate(req.maturityDate());
            if (req.collateralDate() != null)   f.setCollateralDate(req.collateralDate());
            if (req.facilitySize() != null)     f.setFacilitySize(req.facilitySize());
            if (req.ubsParticipation() != null) f.setUbsParticipation(req.ubsParticipation());
            f.setUpdatedAt(LocalDateTime.now());
            Facility saved = repo.save(f);
            log.info("Facility updated id={} name='{}' agentBank='{}'", id, saved.getName(), saved.getAgentBank());
            return ResponseEntity.ok(FacilityDto.from(saved, (int) lpRecordRepo.countByFacilityId(id)));
        }).orElse(ResponseEntity.notFound().build());
    }

    // Hard-delete a facility. Permitted only when it carries no LP records — the
    // service performs the guard and cascades to non-LpRecord dependents. 404 if absent,
    // 409 if it still has LP records.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        facilityService.delete(id);
        notifier.broadcast("Facility deleted (id " + id + ")");
        log.info("Facility deleted id={}", id);
        return ResponseEntity.noContent().build();
    }
}
