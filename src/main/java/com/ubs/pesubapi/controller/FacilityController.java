package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.FacilityDto;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    record CreateFacilityRequest(@NotBlank String name, @NotBlank String agentBank) {}

    private final FacilityRepository repo;
    private final LpRepository lpRepo;
    private final NotificationService notifier;

    public FacilityController(FacilityRepository repo, LpRepository lpRepo, NotificationService notifier) {
        this.repo     = repo;
        this.lpRepo   = lpRepo;
        this.notifier = notifier;
    }

    @GetMapping
    public List<FacilityDto> list() {
        Map<Integer, Integer> lpCounts = new HashMap<>();
        for (Object[] row : lpRepo.countGroupedByFacilityId()) {
            lpCounts.put((Integer) row[0], ((Long) row[1]).intValue());
        }
        return repo.findAll(Sort.by("name")).stream()
            .map(f -> FacilityDto.from(f, lpCounts.getOrDefault(f.getId(), 0)))
            .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<FacilityDto> get(@PathVariable int id) {
        return repo.findById(id)
            .map(f -> FacilityDto.from(f, (int) lpRepo.countByFacilityId(id)))
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
        return ResponseEntity.status(201).body(FacilityDto.from(saved));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<FacilityDto> patchStatus(@PathVariable int id,
                                                    @RequestBody Map<String, String> body) {
        return repo.findById(id).map(f -> {
            String newStatus = body.get("status");
            f.setStatus(newStatus);
            f.setUpdatedAt(LocalDateTime.now());
            Facility saved = repo.save(f);
            notifier.broadcast(f.getName() + " status updated to " + newStatus);
            return ResponseEntity.ok(FacilityDto.from(saved, (int) lpRepo.countByFacilityId(id)));
        }).orElse(ResponseEntity.notFound().build());
    }
}
