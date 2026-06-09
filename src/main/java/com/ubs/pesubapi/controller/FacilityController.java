package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.FacilityDto;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    record CreateFacilityRequest(@NotBlank String name, @NotBlank String agentBank) {}

    private final FacilityRepository repo;
    private final NotificationService notifier;

    public FacilityController(FacilityRepository repo, NotificationService notifier) {
        this.repo     = repo;
        this.notifier = notifier;
    }

    @GetMapping
    public List<FacilityDto> list() {
        return repo.findAll(Sort.by("name")).stream().map(FacilityDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<FacilityDto> get(@PathVariable int id) {
        return repo.findById(id)
            .map(FacilityDto::from)
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
            return ResponseEntity.ok(FacilityDto.from(saved));
        }).orElse(ResponseEntity.notFound().build());
    }
}
