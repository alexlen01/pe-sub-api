package com.ubs.pesubapi.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdvanceRateFloorMapTest {

    @ParameterizedTest
    @CsvSource({
        "100.0, 90%",
        "90.0, 90%",
        "89.9, 75%",
        "75.0, 75%",
        "74.9, 65%",
        "65.0, 65%",
        "64.9, 50%",
        "50.0, 50%",
        "49.9, 0%",
        "0.0, 0%"
    })
    void groupLabel_appliesExplicitFloorBoundaries(double ratePct, String expectedGroup) {
        assertThat(AdvanceRateFloorMap.groupLabel(ratePct)).isEqualTo(expectedGroup);
    }
}
