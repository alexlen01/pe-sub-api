package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Integer> {
    List<Submission> findByFacilityIdOrderByCreatedAtDesc(Integer facilityId);
    List<Submission> findAllByOrderByCreatedAtDesc();
    void deleteByFacilityId(Integer facilityId);
}
