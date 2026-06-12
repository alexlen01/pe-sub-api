package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.LpRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LpRateRepository extends JpaRepository<LpRate, Integer> {

    Optional<LpRate> findByLpIdAndEffectiveDate(Integer lpId, LocalDate effectiveDate);

    @Query("""
        SELECT r FROM LpRate r
        WHERE r.effectiveDate = (
            SELECT MAX(r2.effectiveDate)
            FROM LpRate r2
            WHERE r2.lpId = r.lpId
              AND r2.effectiveDate <= :asOf
        )
        ORDER BY r.lpId
        """)
    List<LpRate> findLatestAsOf(@Param("asOf") LocalDate asOf);
}
