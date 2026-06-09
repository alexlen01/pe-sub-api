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
    LocalDateTime lastRunAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static FacilityDto from(Facility f) {
        return new FacilityDto(
            f.getId(), f.getName(), f.getAgentBank(), f.getStatus(),
            f.getConcLimitM(), f.getLastRunAt(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
