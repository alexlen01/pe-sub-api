package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.Facility;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FacilityDto(
    Integer       id,
    String        name,
    String        agentBank,
    String        status,
    BigDecimal    concLimitM,
    int           lpCount,
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
            f.getConcLimitM(), lpCount, f.getLastRunAt(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
