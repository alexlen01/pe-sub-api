package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.Lp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LpRepository extends JpaRepository<Lp, Integer> {
    List<Lp> findByFacilityIdOrderByInvestorNameAsc(Integer facilityId);

    /**
     * LP records in their natural (source-file) order: by the originating Agent BB row position,
     * falling back to investor name. PostgreSQL ASC sorts NULLs last, so legacy/manually-created
     * rows without a source position appear after the uploaded set, ordered alphabetically.
     */
    List<Lp> findByFacilityIdOrderBySourceSeqAscInvestorNameAsc(Integer facilityId);
    List<Lp> findByFacilityIdAndClsOrderByInvestorNameAsc(Integer facilityId, String cls);
    List<Lp> findByFacilityIdAndInvestorNameContainingIgnoreCaseOrderByInvestorNameAsc(Integer facilityId, String investorName);
    List<Lp> findAllByOrderByInvestorNameAsc();

    long countByFacilityId(Integer facilityId);

    /** LP record counts per facility — [facilityId, count] rows — for the facility list. */
    @Query("SELECT l.facilityId, COUNT(l) FROM Lp l GROUP BY l.facilityId")
    List<Object[]> countGroupedByFacilityId();

    @Query("SELECT DISTINCT l.investorName FROM Lp l ORDER BY l.investorName")
    List<String> findAllDistinctNames();
}
