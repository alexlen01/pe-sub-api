package com.ubs.pesubapi.dto;

import java.util.List;

public record BbResult(
    List<ComputedLpRecord> lps,
    BbSummary        summary,
    List<BbBreach>   breaches
) {}
