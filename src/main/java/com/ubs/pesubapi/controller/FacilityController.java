package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.FacilityRepository;
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

    public FacilityController(FacilityRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Facility> list() {
        return repo.findAll(Sort.by("name"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Facility> get(@PathVariable Integer id) {
        return repo.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Facility> patchStatus(@PathVariable Integer id,
                                                 @RequestBody Map<String, String> body) {
        return repo.findById(id).map(f -> {
            f.setStatus(body.get("status"));
            f.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok(repo.save(f));
        }).orElse(ResponseEntity.notFound().build());
    }

}
