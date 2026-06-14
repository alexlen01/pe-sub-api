package com.ubs.pesubapi.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * Normalises an effective-period string to the first day of its month.
 * <p>
 * LP rates are keyed by reporting month, but callers receive the period from two sources:
 * the documented {@code YYYY-MM} form, and the {@code YYYY-MM-DD} value the Upload screen's
 * date picker stores on {@code submissions.periodMonth} (then forwards as {@code effectiveDate}
 * / {@code effective_date}). Both share the {@code YYYY-MM} prefix, so a stray day component
 * must not reach {@link YearMonth#parse}. Centralised here so every consumer parses identically.
 */
public final class EffectivePeriod {

    private EffectivePeriod() {}

    /**
     * Parses {@code value} to the first day of its month, tolerating both {@code YYYY-MM} and
     * {@code YYYY-MM-DD}.
     *
     * @throws DateTimeParseException if the leading {@code YYYY-MM} cannot be parsed; callers that
     *                                face untrusted input should map this to {@code 400 Bad Request}.
     */
    public static LocalDate firstOfMonth(String value) {
        String trimmed = value.trim();
        String month   = trimmed.length() >= 7 ? trimmed.substring(0, 7) : trimmed;
        return YearMonth.parse(month).atDay(1);
    }
}
