package com.ubs.pesubapi.dto;

import java.time.LocalDateTime;
import java.util.List;

public record IngestResult(
    int facilityId,
    LocalDateTime processedAt,
    String templateFormat,
    List<RecordResult> results,
    int updated,
    int queued,
    int skipped
) {
    public record RecordResult(
        int rowIndex,
        String extractedName,
        Integer matchedLpId,
        String matchedLpName,
        Integer matchScore,
        String action,
        List<String> updatedFields,
        List<String> warnings
    ) {}
}
