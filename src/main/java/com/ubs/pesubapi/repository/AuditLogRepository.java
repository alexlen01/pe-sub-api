package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Integer> {
    List<AuditLog> findAllByOrderByCreatedAtDesc();

    /** Detach audit history from a facility being deleted, preserving the log entries themselves. */
    @Modifying
    @Query("UPDATE AuditLog a SET a.facilityId = null WHERE a.facilityId = :facilityId")
    void clearFacilityRef(@Param("facilityId") Integer facilityId);
}
