package com.ubs.pesubapi.dto;

public record BbSummary(
    double totalUBB,
    double totalABB,
    double bbDelta,
    double ear,
    double agentEar,
    double earDelta,
    int    includedCount,
    int    excludedCount
) {}
