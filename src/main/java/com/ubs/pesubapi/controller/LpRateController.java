package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.LpRateBatchRequest;
import com.ubs.pesubapi.dto.LpRateDto;
import com.ubs.pesubapi.service.LpRateService;
import com.ubs.pesubapi.util.EffectivePeriod;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lps/rates")
public class LpRateController {

    private static final Logger log = LoggerFactory.getLogger(LpRateController.class);

    private final LpRateService lpRateService;

    public LpRateController(LpRateService lpRateService) {
        this.lpRateService = lpRateService;
    }

    /**
     * Returns the most recent rates for every LP on or before the given period.
     * effective_date: YYYY-MM (e.g. 2026-05). Defaults to the current month.
     */
    @GetMapping
    public List<LpRateDto> getRates(
            @RequestParam(name = "effective_date", required = false) String effectiveDateParam) {

        LocalDate asOf = parseEffectiveDate(effectiveDateParam);
        return lpRateService.getRatesAsOf(asOf);
    }

    /**
     * Bulk-upserts LP rates from an ingested feed file.
     * Matches LPs by name (case-insensitive). Idempotent — re-posting the same
     * effective_date overwrites existing rows for those LP IDs.
     */
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Integer> batchUpsert(@Valid @RequestBody LpRateBatchRequest req) {
        int saved = lpRateService.upsertBatch(req);
        log.info("LP rates batch upsert effectiveDate={} rows={} saved={}",
            req.effectiveDate(), req.rates() != null ? req.rates().size() : 0, saved);
        return Map.of("saved", saved);
    }

    private LocalDate parseEffectiveDate(String param) {
        if (param == null || param.isBlank()) return LocalDate.now();
        try {
            // Tolerates YYYY-MM and the YYYY-MM-DD the UI forwards from submission periodMonth.
            return EffectivePeriod.firstOfMonth(param);
        } catch (DateTimeParseException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "effective_date must be YYYY-MM, got: " + param
            );
        }
    }
}
