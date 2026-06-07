package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.BbTemplateGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BbTemplateGroupRepository extends JpaRepository<BbTemplateGroup, Integer> {

    List<BbTemplateGroup> findByTabIdOrderByGroupSortAsc(Integer tabId);

    Optional<BbTemplateGroup> findByTabIdAndHeaderTextIgnoreCase(Integer tabId, String headerText);
}
