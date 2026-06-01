package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.FmSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FmSuggestionRepository extends JpaRepository<FmSuggestion, Integer> {
    List<FmSuggestion> findAllByOrderByCreatedAtDesc();
}
