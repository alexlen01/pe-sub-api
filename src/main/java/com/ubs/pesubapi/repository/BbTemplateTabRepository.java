package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.BbTemplateTab;
import com.ubs.pesubapi.entity.BbTemplateTab.TabRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BbTemplateTabRepository extends JpaRepository<BbTemplateTab, Integer> {

    List<BbTemplateTab> findByTemplateIdOrderByTabSortAsc(Integer templateId);

    Optional<BbTemplateTab> findByTemplateIdAndTabRole(Integer templateId, TabRole tabRole);
}
