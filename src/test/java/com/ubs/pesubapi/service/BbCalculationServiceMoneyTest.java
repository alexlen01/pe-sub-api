package com.ubs.pesubapi.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit coverage for the money helpers behind the borrowing-base engine:
 *  - {@code moneyM} reads the precise numeric column first (C2), keeping full dollars instead of
 *    the rounded display string, and falls back to the string only when the numeric is absent.
 *  - {@code parseMoney} matches the TS reference for suffix-less amounts (C3).
 */
class BbCalculationServiceMoneyTest {

    @Test
    void moneyM_prefersNumericColumn_overRoundedDisplayString() {
        // $12,345,678 rounds to "$12.3M" for display; the numeric column keeps the exact value.
        double m = BbCalculationService.moneyM(new BigDecimal("12345678"), "$12.3M");
        assertThat(m).isEqualTo(12.345678, within(1e-9));
    }

    @Test
    void moneyM_fallsBackToDisplayString_whenNumericNull() {
        // Pre-migration row: no numeric value, so the formatted string is parsed ($millions).
        assertThat(BbCalculationService.moneyM(null, "$12.3M")).isEqualTo(12.3, within(1e-9));
    }

    @Test
    void moneyM_nullNumericAndBlankString_isZero() {
        assertThat(BbCalculationService.moneyM(null, null)).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void parseMoney_subMillionAbsoluteDollars_matchesTsReference() {
        // "$500000" is absolute dollars → 0.5 (million), not 500000 million. Guards the C3 fix.
        assertThat(BbCalculationService.parseMoney("$500000")).isEqualTo(0.5, within(1e-9));
        assertThat(BbCalculationService.parseMoney("$25.0M")).isEqualTo(25.0, within(1e-9));
        assertThat(BbCalculationService.parseMoney("$1.4B")).isEqualTo(1400.0, within(1e-9));
    }
}
