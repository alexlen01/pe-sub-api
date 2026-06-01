package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.FmBlocklistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FmBlocklistRepository extends JpaRepository<FmBlocklistEntry, Integer> {
    List<FmBlocklistEntry> findAllByOrderByIdAsc();
}
