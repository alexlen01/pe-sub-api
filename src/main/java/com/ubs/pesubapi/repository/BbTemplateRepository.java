package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.BbTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BbTemplateRepository extends JpaRepository<BbTemplate, Integer> {
    List<BbTemplate> findAllByAgentBankIgnoreCase(String agentBank);
    Optional<BbTemplate> findByAgentBankIgnoreCaseAndTemplateClass(String agentBank, String templateClass);
}
