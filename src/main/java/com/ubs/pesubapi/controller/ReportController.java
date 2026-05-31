package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.repository.BbSnapshotRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final BbSnapshotRepository snapshotRepo;

    public ReportController(BbSnapshotRepository snapshotRepo) {
        this.snapshotRepo = snapshotRepo;
    }

    @GetMapping("/collateral/{facilityId}")
    public ResponseEntity<?> collateral(@PathVariable int facilityId) {
        return snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .map(snap -> ResponseEntity.ok(Map.of(
                "facilityId",    snap.getFacilityId(),
                "calculatedAt",  snap.getCalculatedAt(),
                "summary",       snap.getResult().summary()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/concentration/{facilityId}")
    public ResponseEntity<?> concentration(@PathVariable int facilityId) {
        return snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .map(snap -> ResponseEntity.ok(Map.of(
                "breaches", snap.getResult().breaches()
            )))
            .orElse(ResponseEntity.notFound().build());
    }
}
