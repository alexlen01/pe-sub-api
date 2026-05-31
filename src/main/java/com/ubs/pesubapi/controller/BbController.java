package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.service.BbCalculationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/bb")
public class BbController {

    private final FacilityRepository    facilityRepo;
    private final LpRepository          lpRepo;
    private final BbSnapshotRepository  snapshotRepo;
    private final BbCalculationService  calculator;

    public BbController(FacilityRepository facilityRepo, LpRepository lpRepo,
                        BbSnapshotRepository snapshotRepo, BbCalculationService calculator) {
        this.facilityRepo = facilityRepo;
        this.lpRepo       = lpRepo;
        this.snapshotRepo = snapshotRepo;
        this.calculator   = calculator;
    }

    @PostMapping("/run/{facilityId}")
    public ResponseEntity<BbSnapshot> run(@PathVariable Integer facilityId) {
        Facility facility = facilityRepo.findById(facilityId)
            .orElseThrow(() -> new ResourceNotFoundException("Facility not found: " + facilityId));

        BbResult result = calculator.compute(
            lpRepo.findByFacilityIdOrderByRankAsc(facilityId),
            facility.getConcLimitM().doubleValue()
        );

        BbSnapshot snapshot = new BbSnapshot();
        snapshot.setFacilityId(facilityId);
        snapshot.setResult(result);
        BbSnapshot saved = snapshotRepo.save(snapshot);

        facility.setLastRunAt(LocalDateTime.now());
        facilityRepo.save(facility);

        return ResponseEntity.status(201).body(saved);
    }

    @GetMapping("/snapshots/{facilityId}")
    public List<BbSnapshot> snapshots(@PathVariable Integer facilityId) {
        return snapshotRepo.findByFacilityIdOrderByCalculatedAtAsc(facilityId);
    }

    @GetMapping("/snapshots/{facilityId}/latest")
    public ResponseEntity<BbSnapshot> latestSnapshot(@PathVariable Integer facilityId) {
        return snapshotRepo.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
