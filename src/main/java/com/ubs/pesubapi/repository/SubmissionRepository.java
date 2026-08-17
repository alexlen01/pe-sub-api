package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Integer> {
    List<Submission> findByFacilityIdOrderByCreatedAtDesc(Integer facilityId);
    List<Submission> findAllByOrderByCreatedAtDesc();

    /** Newest submission in one of the given states — the wizard's "currently open" submission.
     *  Bounded to a single row because it is consulted on every classification auto-save. */
    Optional<Submission> findFirstByFacilityIdAndStatusInOrderByCreatedAtDesc(
        Integer facilityId, Collection<String> statuses);

    void deleteByFacilityId(Integer facilityId);
}
