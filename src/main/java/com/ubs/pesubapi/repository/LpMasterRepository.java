package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.LpMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LpMasterRepository extends JpaRepository<LpMaster, Integer> {

    List<LpMaster> findAllByOrderByUbsLpCategoryAscInvestorNameAsc();

    Optional<LpMaster> findByInvestorName(String investorName);

    List<LpMaster> findByInvestorNameIn(Collection<String> investorNames);

    /** All canonical LP names ordered alphabetically — used as the fuzzy-match candidate pool. */
    @Query("SELECT m.investorName FROM LpMaster m ORDER BY m.investorName")
    List<String> findAllInvestorNames();

    /** Direct children of an LP Master row — the feeders/SPVs that route their credit profile to it. */
    List<LpMaster> findByParentIdOrderByInvestorNameAsc(Integer parentId);

    /** Rows naming {@code investorName} as their parent but not yet linked, for backfill on write. */
    List<LpMaster> findByParentAndParentIdIsNull(String parent);

    /** Every row's (id, parentId, investorName) triple — the parent chain, without loading full rows. */
    @Query("SELECT m.id, m.parentId, m.investorName FROM LpMaster m")
    List<Object[]> findParentLinks();

    /** Distinct investor types currently present in LP Master, used to keep UI picklists data-driven. */
    @Query("SELECT DISTINCT m.investorType FROM LpMaster m WHERE m.investorType IS NOT NULL AND m.investorType <> '' ORDER BY m.investorType")
    List<String> findDistinctInvestorTypes();
}
