package com.ubs.pesubapi.dto;

import java.time.LocalDateTime;

/** One snapshot's effective-advance-rate figures for the EAR trend report. */
public record EarPointDto(
    LocalDateTime calculatedAt,
    double        ear,
    double        agentEar,
    double        earDelta
) {}
