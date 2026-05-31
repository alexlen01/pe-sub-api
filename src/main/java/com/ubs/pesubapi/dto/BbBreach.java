package com.ubs.pesubapi.dto;

public record BbBreach(
    String type,
    String severity,
    String message,
    double value,
    double limit
) {}
