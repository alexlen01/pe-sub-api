package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.BbSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BbSnapshotRepository extends JpaRepository<BbSnapshot, Integer> {
    List<BbSnapshot> findByFacilityIdOrderByCalculatedAtAsc(Integer facilityId);
    Optional<BbSnapshot> findTopByFacilityIdOrderByCalculatedAtDesc(Integer facilityId);
}
