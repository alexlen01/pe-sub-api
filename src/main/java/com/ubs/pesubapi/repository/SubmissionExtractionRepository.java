package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.SubmissionExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubmissionExtractionRepository extends JpaRepository<SubmissionExtraction, Integer> {
    Optional<SubmissionExtraction> findBySubmissionId(Integer submissionId);
    void deleteBySubmissionId(Integer submissionId);
}
