package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.Lp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LpRepository extends JpaRepository<Lp, Integer> {
    List<Lp> findByFacilityIdOrderByRankAsc(Integer facilityId);
    List<Lp> findByFacilityIdAndClsOrderByRankAsc(Integer facilityId, String cls);
    List<Lp> findByFacilityIdAndNameContainingIgnoreCaseOrderByRankAsc(Integer facilityId, String name);
    List<Lp> findAllByOrderByRankAsc();
}
