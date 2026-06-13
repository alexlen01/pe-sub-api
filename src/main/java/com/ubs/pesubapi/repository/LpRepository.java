package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.Lp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LpRepository extends JpaRepository<Lp, Integer> {
    List<Lp> findByFacilityIdOrderByInvestorNameAsc(Integer facilityId);
    List<Lp> findByFacilityIdAndClsOrderByInvestorNameAsc(Integer facilityId, String cls);
    List<Lp> findByFacilityIdAndInvestorNameContainingIgnoreCaseOrderByInvestorNameAsc(Integer facilityId, String investorName);
    List<Lp> findAllByOrderByInvestorNameAsc();

    @Query("SELECT DISTINCT l.investorName FROM Lp l ORDER BY l.investorName")
    List<String> findAllDistinctNames();
}
