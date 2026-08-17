package com.ubs.pesubapi.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import com.ubs.pesubapi.service.BbCalculationService;

/**
 * Conversions between the formatted money/percentage strings that arrive on the wire and the
 * NUMERIC columns on {@code lp_records}.
 *
 * <p>The money columns (cap_commit, uncalled_capital, aum, agent_bb) and the concentration limits
 * (agent_conc_limit, ubs_conc_limit) are stored as {@code NUMERIC(20,2)}. Inbound DTOs still carry
 * them as strings, so every write path funnels through here rather than keeping a private copy of
 * the same parse.
 *
 * <p>Percent/rate columns are {@code NUMERIC(7,4)} fractions (0.9100 = 91%) and, unlike money, are
 * numeric on the wire as well — {@link #fraction} exists only to normalise legacy percent-scaled or
 * string inputs on the way in. There is no percent display formatter here: pe-sub-ui renders them.
 */
public final class MoneyValues {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);
    private static final BigDecimal MILLION  = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal BILLION  = BigDecimal.valueOf(1_000_000_000L);

    private MoneyValues() {
    }

    /**
     * Formatted money string (e.g. {@code "$12,345,678.9"}) to absolute dollars, or null when blank
     * or unparseable so the column is left empty rather than defaulted to zero.
     */
    public static BigDecimal dollars(String display) {
        double millions = BbCalculationService.parseMoney(display);
        return millions == 0 ? null : BigDecimal.valueOf(millions * 1_000_000.0);
    }

    /**
     * Percentage or plain decimal string (e.g. {@code "7.5%"}, {@code "0.075"}) to BigDecimal, or
     * null when blank or unparseable.
     */
    public static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) return null;
        String clean = s.replaceAll("[%,]", "").trim();
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A percent from any inbound representation to the stored fraction: {@code "91%"}, {@code "91"}
     * and {@code 91} all become {@code 0.91}, while {@code "0.91"} and {@code 0.91} pass through.
     * Null when blank or unparseable, so the column is left empty rather than defaulted to zero.
     *
     * <p>The magnitude rule ({@code > 1} means percent-scaled) is the same one
     * {@code BbCalculationService.parsePctDecimal} applies on read and {@code advanceRateFraction}
     * applied to the old rate strings — a rate above 100% is not representable, and never was.
     */
    public static BigDecimal fraction(Object raw) {
        BigDecimal value = switch (raw) {
            case null -> null;
            case BigDecimal bd -> bd;
            case Number n -> BigDecimal.valueOf(n.doubleValue());
            case String s -> decimal(s);
            default -> null;
        };
        if (value == null) return null;
        return value.abs().compareTo(BigDecimal.ONE) > 0
            ? value.divide(BigDecimal.valueOf(100))
            : value;
    }

    /**
     * A funding ratio to its stored fraction. Unlike {@link #fraction}, magnitude carries no meaning
     * here — a pension can be over-funded, so {@code 1.12} is a legitimate ratio (112% funded) and
     * must not be read as percent-scaled. Only an explicit percent sign rescales: {@code "112%"} and
     * {@code "98%"} become {@code 1.12} and {@code 0.98}, while {@code 1.12} and {@code "1.12"}
     * pass through untouched. Null when blank or unparseable.
     */
    public static BigDecimal ratio(Object raw) {
        return switch (raw) {
            case null -> null;
            case BigDecimal bd -> bd;
            case Number n -> BigDecimal.valueOf(n.doubleValue());
            case String s -> {
                BigDecimal value = decimal(s);
                yield value != null && s.indexOf('%') >= 0
                    ? value.divide(BigDecimal.valueOf(100))
                    : value;
            }
            default -> null;
        };
    }

    /**
     * A concentration limit, which is expressed either as a percentage of total uncalled capital
     * ({@code "7.5%"}) or as an absolute dollar cap ({@code "$25.0M"}). Both collapse into the one
     * NUMERIC column and are told apart on read by magnitude — see
     * {@link com.ubs.pesubapi.service.BbCalculationService#ABSOLUTE_DOLLAR_MIN}: percentages stay on
     * the percent scale (7.5), dollar caps are stored in absolute dollars (25000000).
     */
    public static BigDecimal concLimit(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf('%') >= 0) return decimal(s);
        BigDecimal plain = decimal(s);       // bare number → percent scale by convention
        return plain != null ? plain : dollars(s);
    }

    /** NUMERIC column to its string form for DTOs that still expose money as text; null stays null. */
    public static String text(BigDecimal value) {
        return value != null ? value.toString() : null;
    }

    /**
     * A stored concentration limit back to its display form, applying the same magnitude rule
     * {@link #concLimit} used on the way in: an absolute-dollar cap renders as money
     * ({@code "$25,000,000"}), a percentage as a percent ({@code "7.5%"}).
     */
    public static String concLimitText(BigDecimal value) {
        if (value == null) return null;
        if (value.abs().doubleValue() >= BbCalculationService.ABSOLUTE_DOLLAR_MIN) return display(value);
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * Full-precision dollar display: thousands grouping, every digit kept, no unit abbreviation —
     * "$1,999,999" must never render (or persist) as "$2.0M". Used when a NUMERIC lp_records column
     * feeds a column that is still VARCHAR, such as lp_master's financial-scale fields.
     */
    public static String display(BigDecimal value) {
        if (value == null) return null;
        DecimalFormat money = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        money.setMaximumFractionDigits(20);
        return "$" + money.format(value);
    }

    /**
     * Compact dollar display ({@code "$4.25B"}, {@code "$314.6M"}, {@code "$8B"}) — the form an agent
     * writes a financial-scale figure in, and how a numeric extraction value is stored in the VARCHAR
     * scale columns. Exact: the unit is chosen by magnitude and trailing zeros are trimmed, never
     * rounded, so {@link #expand} recovers the original amount.
     */
    public static String shortDisplay(BigDecimal value) {
        if (value == null) return null;
        BigDecimal magnitude = value.abs();
        String suffix = "";
        BigDecimal divisor = BigDecimal.ONE;
        if (magnitude.compareTo(BILLION) >= 0)      { divisor = BILLION;  suffix = "B"; }
        else if (magnitude.compareTo(MILLION) >= 0) { divisor = MILLION;  suffix = "M"; }
        else if (magnitude.compareTo(THOUSAND) >= 0) { divisor = THOUSAND; suffix = "K"; }
        BigDecimal body = value.divide(divisor).stripTrailingZeros();
        String sign = body.signum() < 0 ? "-" : "";
        return sign + "$" + body.abs().toPlainString() + suffix;
    }

    /**
     * A financial-scale string in any inbound form ({@code "$4.2B"}, {@code "4250000000"}) to the
     * full-precision display the UI reads ({@code "$4,200,000,000"}) — abbreviations are expanded
     * once and never re-abbreviated, and an already-expanded value passes through unchanged.
     * Text that carries no readable amount (a range, {@code "N/A"}, free notes) is returned verbatim
     * rather than dropped, since these columns are passthrough by design.
     */
    public static String expand(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        BigDecimal amount = dollars(raw);
        return amount != null ? display(amount) : raw;
    }
}
