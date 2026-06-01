package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Integer> {
    List<AuditLog> findAllByOrderByCreatedAtDesc();
}
