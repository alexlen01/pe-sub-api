package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.LpMasterDto;
import com.ubs.pesubapi.repository.LpMasterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/lp-master")
public class LpMasterController {

    private static final Logger log = LoggerFactory.getLogger(LpMasterController.class);

    private final LpMasterRepository repo;

    public LpMasterController(LpMasterRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<LpMasterDto> list() {
        List<LpMasterDto> result = repo.findAll(Sort.by("ubsClassification").ascending().and(Sort.by("investorName").ascending()))
                .stream()
                .map(LpMasterDto::from)
                .toList();
        log.info("LP Master listed count={}", result.size());
        return result;
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("count", repo.count());
    }

    @GetMapping("/investor-types")
    public List<String> investorTypes() {
        List<String> result = repo.findDistinctInvestorTypes().stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim())
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        log.info("LP Master investor types listed count={}", result.size());
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<LpMasterDto> get(@PathVariable int id) {
        return repo.findById(id)
                .map(LpMasterDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
