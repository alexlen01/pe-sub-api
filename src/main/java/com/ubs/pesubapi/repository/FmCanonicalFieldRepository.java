package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.FmCanonicalField;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FmCanonicalFieldRepository extends JpaRepository<FmCanonicalField, Integer> {
    List<FmCanonicalField> findAllByOrderByGroupSortAscFieldSortAsc();
}
