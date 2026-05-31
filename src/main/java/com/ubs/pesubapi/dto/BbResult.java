package com.ubs.pesubapi.dto;

import java.util.List;

public record BbResult(
    List<ComputedLp> lps,
    BbSummary        summary,
    List<BbBreach>   breaches
) {}
