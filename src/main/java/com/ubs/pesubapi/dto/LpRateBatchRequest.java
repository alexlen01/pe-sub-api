package com.ubs.pesubapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record LpRateBatchRequest(

    @NotBlank
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "effectiveDate must be YYYY-MM")
    String effectiveDate,

    @NotEmpty @Valid
    List<Entry> rates

) {
    public record Entry(

        @NotBlank
        String lpName,

        @NotBlank
        String classification,

        @DecimalMin("0.0") @DecimalMax("1.0")
        double ubsAdvRatePct,

        @DecimalMin("0.0") @DecimalMax("1.0")
        double ubsConcLimitPct,

        String source
    ) {}
}
