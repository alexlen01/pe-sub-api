package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.LpRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LpRecordRepository extends JpaRepository<LpRecord, Integer> {
    List<LpRecord> findByFacilityIdOrderByInvestorNameAsc(Integer facilityId);

    /**
     * LP records in their natural (source-file) order: by the originating Agent BB row position,
     * falling back to investor name. PostgreSQL ASC sorts NULLs last, so legacy/manually-created
     * rows without a source position appear after the uploaded set, ordered alphabetically.
     */
    List<LpRecord> findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(Integer facilityId);
    List<LpRecord> findByFacilityIdAndClsOrderByClsAscInvestorNameAsc(Integer facilityId, String cls);
    List<LpRecord> findByFacilityIdAndInvestorNameContainingIgnoreCaseOrderByClsAscInvestorNameAsc(Integer facilityId, String investorName);
    List<LpRecord> findAllByOrderByInvestorNameAsc();
    List<LpRecord> findAllByOrderByClsAscInvestorNameAsc();

    long countByFacilityId(Integer facilityId);

    /** Seed-ingest guard: (facility, investor) pairs already present are left untouched. */
    boolean existsByFacilityIdAndInvestorName(Integer facilityId, String investorName);

    /** LP record counts per facility — [facilityId, count] rows — for the facility list. */
    @Query("SELECT l.facilityId, COUNT(l) FROM LpRecord l GROUP BY l.facilityId")
    List<Object[]> countGroupedByFacilityId();

    void deleteByFacilityId(Integer facilityId);

    @Query("SELECT DISTINCT l.investorName FROM LpRecord l ORDER BY l.investorName")
    List<String> findAllDistinctNames();

    /** Detach facility LP records from an LP Master row being deleted, preserving the
     *  facility-level data itself. Returns the number of records detached. */
    @Modifying
    @Query("UPDATE LpRecord l SET l.lpMasterId = null WHERE l.lpMasterId = :lpMasterId")
    int clearLpMasterRef(@Param("lpMasterId") Integer lpMasterId);

    /** Detach every facility LP record from LP Master ahead of a full LP Master replace,
     *  preserving the facility-level data. Returns the number of records detached. */
    @Modifying
    @Query("UPDATE LpRecord l SET l.lpMasterId = null WHERE l.lpMasterId IS NOT NULL")
    int clearAllLpMasterRefs();

    /** Finalize approved reclassifications. The accepted snapshot retains the historical flag,
     * while live LP records return to their normal post-review state. */
    @Modifying
    @Query("UPDATE LpRecord l SET l.rcl = false WHERE l.facilityId = :facilityId AND l.rcl = true")
    int clearReclassifiedByFacilityId(@Param("facilityId") Integer facilityId);
}
