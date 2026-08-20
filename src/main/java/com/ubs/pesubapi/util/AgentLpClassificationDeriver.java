package com.ubs.pesubapi.util;

import java.util.List;
import java.util.Locale;

public final class AgentLpClassificationDeriver {

    private AgentLpClassificationDeriver() {
    }

    public static String derive(
        String investorType,
        String sp,
        String mdy,
        String fitch,
        String lpSizeBil,
        String lpSizeCriteria,
        String notes,
        boolean spv
    ) {
        String type = lower(investorType);
        String noteText = lower(notes);
        if (spv || containsHardExclusion(type + " " + noteText)) return "Excluded Investor";
        if (hasInvestmentGradeRating(sp, mdy, fitch)) return "Rated Included";
        if (type.matches(".*(family office|hnw|high net worth).*")) return "Excluded Investor";
        if (type.matches(".*(institutional|endowment|foundation|insurance|sovereign|pension|corporate|healthcare|fund of funds|fof|investment consultant|hedge fund).*")) {
            return "Non-Rated Included";
        }
        return null;
    }

    private static boolean containsHardExclusion(String text) {
        return text.matches(".*\\b(sanction|bad actor|kyc|erisa|side letter|non[-\\s]?cooperative|ineligible|excluded|gp sleeve|sponsor affiliate|affiliate)\\b.*");
    }

    private static boolean hasInvestmentGradeRating(String sp, String mdy, String fitch) {
        return isSpInvestmentGrade(sp) || isMoodysInvestmentGrade(mdy) || isSpInvestmentGrade(fitch);
    }

    private static boolean isSpInvestmentGrade(String raw) {
        String rating = upper(raw);
        return List.of("AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB+", "BBB", "BBB-").contains(rating);
    }

    private static boolean isMoodysInvestmentGrade(String raw) {
        String rating = upper(raw);
        return List.of("AAA", "AA1", "AA2", "AA3", "A1", "A2", "A3", "BAA1", "BAA2", "BAA3").contains(rating);
    }

    private static String lower(String raw) {
        return raw != null ? raw.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static String upper(String raw) {
        return raw != null ? raw.trim().toUpperCase(Locale.ROOT) : "";
    }
}
