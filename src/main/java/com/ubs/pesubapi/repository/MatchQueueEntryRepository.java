package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.MatchQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchQueueEntryRepository extends JpaRepository<MatchQueueEntry, Integer> {
    List<MatchQueueEntry> findBySubmissionIdOrderByRowIndexAsc(Integer submissionId);
    List<MatchQueueEntry> findByFacilityIdOrderByRowIndexAsc(Integer facilityId);
    void deleteBySubmissionId(Integer submissionId);
    void deleteByFacilityId(Integer facilityId);
}
