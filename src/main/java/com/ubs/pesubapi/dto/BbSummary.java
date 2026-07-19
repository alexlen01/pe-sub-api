package com.ubs.pesubapi.dto;

public record BbSummary(
    double totalUBB,
    double totalABB,
    double bbDelta,
    double ear,
    double agentEar,
    double earDelta,
    double totalUEC,
    double totalUC,
    double totalConcExcess,
    int    includedCount,
    int    excludedCount,
    int    reclassCount
) {}
