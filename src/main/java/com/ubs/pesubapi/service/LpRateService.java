package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.LpRateBatchRequest;
import com.ubs.pesubapi.dto.LpRateDto;
import com.ubs.pesubapi.entity.LpRate;
import com.ubs.pesubapi.repository.LpRateRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LpRateService {

    private final LpRateRepository rateRepo;
    private final LpRecordRepository     lpRecordRepo;

    public LpRateService(LpRateRepository rateRepo, LpRecordRepository lpRecordRepo) {
        this.rateRepo = rateRepo;
        this.lpRecordRepo   = lpRecordRepo;
    }

    public List<LpRateDto> getRatesAsOf(LocalDate asOf) {
        List<LpRate> rates = rateRepo.findLatestAsOf(asOf);
        if (rates.isEmpty()) return List.of();

        Map<Integer, String> nameById = lpRecordRepo.findAllByOrderByInvestorNameAsc().stream()
            .collect(Collectors.toMap(lpRecord -> lpRecord.getId(), lpRecord -> lpRecord.getInvestorName()));

        return rates.stream()
            .map(r -> LpRateDto.from(r, nameById.getOrDefault(r.getLpId(), "Unknown")))
            .toList();
    }

    @Transactional
    public int upsertBatch(LpRateBatchRequest req) {
        YearMonth ym = YearMonth.parse(req.effectiveDate());
        LocalDate effectiveDate = ym.atDay(1);

        Map<String, Integer> idByName = lpRecordRepo.findAllByOrderByInvestorNameAsc().stream()
            .collect(Collectors.toMap(
                lpRecord -> lpRecord.getInvestorName().toLowerCase(),
                lpRecord -> lpRecord.getId(),
                (a, b) -> a  // keep first on duplicate name
            ));

        int count = 0;
        for (LpRateBatchRequest.Entry entry : req.rates()) {
            Integer lpId = idByName.get(entry.lpName().toLowerCase());
            if (lpId == null) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No LP found with name: " + entry.lpName()
            );

            LpRate rate = rateRepo.findByLpIdAndEffectiveDate(lpId, effectiveDate)
                .orElseGet(LpRate::new);

            rate.setLpId(lpId);
            rate.setEffectiveDate(effectiveDate);
            rate.setClassification(entry.classification());
            rate.setUbsAdvRatePct(BigDecimal.valueOf(entry.ubsAdvRatePct()));
            rate.setUbsConcLimitPct(BigDecimal.valueOf(entry.ubsConcLimitPct()));
            rate.setSource(entry.source() != null ? entry.source() : "BATCH_FEED");
            rateRepo.save(rate);
            count++;
        }
        return count;
    }
}
