package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.MatchQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MatchQueueEntryRepository extends JpaRepository<MatchQueueEntry, Integer> {
    List<MatchQueueEntry> findBySubmissionIdOrderByRowIndexAsc(Integer submissionId);
    List<MatchQueueEntry> findByFacilityIdOrderByRowIndexAsc(Integer facilityId);
    void deleteBySubmissionId(Integer submissionId);
    void deleteByFacilityId(Integer facilityId);

    @Modifying
    @Query("UPDATE MatchQueueEntry m SET m.matchedLpId = null WHERE m.facilityId = :facilityId")
    int clearMatchedLpIdsForFacility(@Param("facilityId") Integer facilityId);

    /** Detach match-queue entries from an LP record being deleted, keeping their decision history. */
    @Modifying
    @Query("UPDATE MatchQueueEntry m SET m.matchedLpId = null WHERE m.matchedLpId = :lpId")
    int clearMatchedLpRef(@Param("lpId") Integer lpId);
}
