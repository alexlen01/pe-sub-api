package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.ReportHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportHistoryRepository extends JpaRepository<ReportHistory, Integer> {
    List<ReportHistory> findTop50ByOrderByCreatedAtDesc();
}
