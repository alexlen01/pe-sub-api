package com.ubs.pesubapi.service;

import com.ubs.pesubapi.util.MoneyValues;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit coverage for the money helpers behind the borrowing-base engine:
 *  - {@code dollarM} converts the NUMERIC money columns (absolute dollars) to the $millions the
 *    engine calculates in, keeping full precision instead of a rounded display string.
 *  - {@code parseMoney} matches the TS reference for suffix-less amounts (C3).
 *  - {@code MoneyValues.concLimit} keeps percentage and absolute-dollar concentration limits
 *    distinguishable now that both share the one NUMERIC column.
 */
class BbCalculationServiceMoneyTest {

    @Test
    void dollarM_convertsAbsoluteDollarsToMillions_atFullPrecision() {
        // $12,345,678 rounds to "$12.3M" for display; the NUMERIC column keeps the exact value.
        assertThat(BbCalculationService.dollarM(new BigDecimal("12345678")))
            .isEqualTo(12.345678, within(1e-9));
    }

    @Test
    void dollarM_nullIsZero() {
        assertThat(BbCalculationService.dollarM(null)).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void dollarM_keepsPositiveOnlyCalculationInputsUnsigned() {
        assertThat(BbCalculationService.dollarM(new BigDecimal("-1250000")))
            .isEqualTo(1.25, within(1e-9));
    }

    @Test
    void parseMoney_subMillionAbsoluteDollars_matchesTsReference() {
        // "$500000" is absolute dollars → 0.5 (million), not 500000 million. Guards the C3 fix.
        assertThat(BbCalculationService.parseMoney("$500000")).isEqualTo(0.5, within(1e-9));
        assertThat(BbCalculationService.parseMoney("$25.0M")).isEqualTo(25.0, within(1e-9));
        assertThat(BbCalculationService.parseMoney("$1.4B")).isEqualTo(1400.0, within(1e-9));
    }

    @Test
    void parseMoney_recognizesAccountingAndTrailingMinus_notation() {
        assertThat(BbCalculationService.parseMoney("($1,250,000)"))
            .isEqualTo(-1.25, within(1e-9));
        assertThat(BbCalculationService.parseMoney("$1,250,000-"))
            .isEqualTo(-1.25, within(1e-9));
    }

    // ── Concentration limits: percent and dollar caps share one NUMERIC column ──────────

    @Test
    void concLimit_percentageStaysOnPercentScale() {
        assertThat(MoneyValues.concLimit("7.5%")).isEqualByComparingTo(new BigDecimal("7.5"));
        assertThat(MoneyValues.concLimit("25")).isEqualByComparingTo(new BigDecimal("25"));
    }

    @Test
    void concLimit_dollarCapBecomesAbsoluteDollars() {
        // A dollar-denominated limit must survive the numeric column rather than parse to null —
        // it lands above ABSOLUTE_DOLLAR_MIN, which is how the engine tells it from a percentage.
        assertThat(MoneyValues.concLimit("$25.0M"))
            .isEqualByComparingTo(new BigDecimal("25000000"));
        assertThat(MoneyValues.concLimit("$25.0M").doubleValue())
            .isGreaterThanOrEqualTo(BbCalculationService.ABSOLUTE_DOLLAR_MIN);
    }

    @Test
    void concLimit_blankIsNull() {
        assertThat(MoneyValues.concLimit(null)).isNull();
        assertThat(MoneyValues.concLimit("   ")).isNull();
    }
}
