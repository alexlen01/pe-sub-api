package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.LpRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LpRecordRepository extends JpaRepository<LpRecord, Integer> {
    List<LpRecord> findByFacilityIdOrderByInvestorNameAsc(Integer facilityId);

    /**
     * LP records in their natural (source-file) order: by the originating Agent BB row position,
     * falling back to investor name. PostgreSQL ASC sorts NULLs last, so legacy/manually-created
     * rows without a source position appear after the uploaded set, ordered alphabetically.
     */
    List<LpRecord> findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(Integer facilityId);
    List<LpRecord> findByFacilityIdAndClsOrderByInvestorNameAsc(Integer facilityId, String cls);
    List<LpRecord> findByFacilityIdAndInvestorNameContainingIgnoreCaseOrderByInvestorNameAsc(Integer facilityId, String investorName);
    List<LpRecord> findAllByOrderByInvestorNameAsc();

    long countByFacilityId(Integer facilityId);

    /** LP record counts per facility — [facilityId, count] rows — for the facility list. */
    @Query("SELECT l.facilityId, COUNT(l) FROM LpRecord l GROUP BY l.facilityId")
    List<Object[]> countGroupedByFacilityId();

    void deleteByFacilityId(Integer facilityId);

    @Query("SELECT DISTINCT l.investorName FROM LpRecord l ORDER BY l.investorName")
    List<String> findAllDistinctNames();
}
