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
    BigDecimal    facilitySize,
    BigDecimal    ubsParticipation,
    LocalDate     maturityDate,
    String        bankStatus,
    LocalDate     bankStatusDate,
    // Latest Shadow BB figures from the most recent snapshot ($millions); null until a BB is run.
    Double        agentBB,
    Double        ubsBB,
    Double        bbDelta,
    Double        ear,
    LocalDateTime lastRunAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static FacilityDto from(Facility f) {
        return from(f, 0, null);
    }

    public static FacilityDto from(Facility f, int lpCount) {
        return from(f, lpCount, null);
    }

    public static FacilityDto from(Facility f, int lpCount, BbSummary summary) {
        return new FacilityDto(
            f.getId(), f.getName(), f.getAgentBank(), f.getStatus(),
            f.getConcLimitM(), lpCount,
            f.getAccountNumber(), f.getLoanAmount(),
            f.getFacilitySize(), f.getUbsParticipation(), f.getMaturityDate(),
            f.getBankStatus(), f.getBankStatusDate(),
            summary != null ? summary.totalABB() : null,
            summary != null ? summary.totalUBB() : null,
            summary != null ? summary.bbDelta()  : null,
            summary != null ? summary.ear()      : null,
            f.getLastRunAt(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
