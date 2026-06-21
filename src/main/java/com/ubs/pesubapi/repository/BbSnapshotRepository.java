package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.BbSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BbSnapshotRepository extends JpaRepository<BbSnapshot, Integer> {
    List<BbSnapshot> findByFacilityIdOrderByCalculatedAtAsc(Integer facilityId);
    Optional<BbSnapshot> findTopByFacilityIdOrderByCalculatedAtDesc(Integer facilityId);
    void deleteByFacilityId(Integer facilityId);

    /** The most recent snapshot for every facility that has at least one — used to surface the
     *  latest Shadow BB figures (Agent BB / UBS BB / delta / EAR) on the facility list without an
     *  N+1 query per facility. */
    @Query("""
        SELECT s FROM BbSnapshot s
        WHERE s.calculatedAt = (
            SELECT MAX(s2.calculatedAt) FROM BbSnapshot s2 WHERE s2.facilityId = s.facilityId
        )
        """)
    List<BbSnapshot> findLatestPerFacility();
}
