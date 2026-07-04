package com.ubs.pesubapi.dto;

public record CreateReportHistoryRequest(
    String  report,
    Integer facilityId,
    String  snapshotLabel,
    String  format
) {}
