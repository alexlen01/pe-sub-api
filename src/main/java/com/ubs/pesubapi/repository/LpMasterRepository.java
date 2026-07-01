package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.LpMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LpMasterRepository extends JpaRepository<LpMaster, Integer> {

    Optional<LpMaster> findByInvestorName(String investorName);

    /** All canonical LP names ordered alphabetically — used as the fuzzy-match candidate pool. */
    @Query("SELECT m.investorName FROM LpMaster m ORDER BY m.investorName")
    List<String> findAllInvestorNames();
}
