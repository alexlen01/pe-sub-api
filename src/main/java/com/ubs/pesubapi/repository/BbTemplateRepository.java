package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.BbTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BbTemplateRepository extends JpaRepository<BbTemplate, Integer> {
    List<BbTemplate> findAllByTemplateNameIgnoreCase(String templateName);
    Optional<BbTemplate> findByTemplateNameIgnoreCaseAndTemplateClass(String templateName, String templateClass);
    Optional<BbTemplate> findByTemplateSlug(String templateSlug);
}
