package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.ReportHistory;

import java.time.LocalDateTime;

public record ReportHistoryDto(
    Integer       id,
    String        report,
    Integer       facilityId,
    String        facilityName,
    String        snapshotLabel,
    String        format,
    String        userName,
    LocalDateTime createdAt
) {
    public static ReportHistoryDto from(ReportHistory h) {
        return new ReportHistoryDto(
            h.getId(), h.getReport(), h.getFacilityId(), h.getFacilityName(),
            h.getSnapshotLabel(), h.getFormat(), h.getUserName(), h.getCreatedAt()
        );
    }
}
