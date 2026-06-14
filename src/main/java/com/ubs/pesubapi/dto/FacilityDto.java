package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.Facility;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record FacilityDto(
    Integer       id,
    String        name,
    String        agentBank,
    String        status,
    BigDecimal    concLimitM,
    int           lpCount,
    String        accountNumber,
    BigDecimal    loanAmount,
    LocalDate     maturityDate,
    String        bankStatus,
    LocalDate     bankStatusDate,
    LocalDateTime lastRunAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static FacilityDto from(Facility f) {
        return from(f, 0);
    }

    public static FacilityDto from(Facility f, int lpCount) {
        return new FacilityDto(
            f.getId(), f.getName(), f.getAgentBank(), f.getStatus(),
            f.getConcLimitM(), lpCount,
            f.getAccountNumber(), f.getLoanAmount(), f.getMaturityDate(),
            f.getBankStatus(), f.getBankStatusDate(),
            f.getLastRunAt(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
