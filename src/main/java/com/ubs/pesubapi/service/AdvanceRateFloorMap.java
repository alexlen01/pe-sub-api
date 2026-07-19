package com.ubs.pesubapi.service;

/**
 * Maps an advance-rate percentage to its reporting floor:
 * [90, +inf) -> 90%, [75, 90) -> 75%, [65, 75) -> 65%,
 * [50, 65) -> 50%, and anything below 50% -> 0%.
 */
public final class AdvanceRateFloorMap {

    private AdvanceRateFloorMap() {
    }

    public static String groupLabel(double ratePct) {
        if (ratePct >= 90.0) return "90%";
        if (ratePct >= 75.0 && ratePct < 90.0) return "75%";
        if (ratePct >= 65.0 && ratePct < 75.0) return "65%";
        if (ratePct >= 50.0 && ratePct < 65.0) return "50%";
        return "0%";
    }
}
