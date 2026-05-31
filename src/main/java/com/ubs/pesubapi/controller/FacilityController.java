package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.service.NotificationService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityRepository repo;
    private final NotificationService notifier;

    public FacilityController(FacilityRepository repo, NotificationService notifier) {
        this.repo     = repo;
        this.notifier = notifier;
    }

    @GetMapping
    public List<Facility> list() {
        return repo.findAll(Sort.by("name"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Facility> get(@PathVariable int id) {
        return repo.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Facility> patchStatus(@PathVariable int id,
                                                 @RequestBody Map<String, String> body) {
        return repo.findById(id).map(f -> {
            String newStatus = body.get("status");
            f.setStatus(newStatus);
            f.setUpdatedAt(LocalDateTime.now());
            Facility saved = repo.save(f);
            notifier.broadcast(f.getName() + " status updated to " + newStatus);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }
}
