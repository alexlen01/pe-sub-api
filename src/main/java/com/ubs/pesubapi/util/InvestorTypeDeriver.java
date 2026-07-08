package com.ubs.pesubapi.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class InvestorTypeDeriver {

    private InvestorTypeDeriver() {
    }

    private static final Pattern FAMILY_OFFICE = Pattern.compile(
        "\\b(family offices?|whittier trust|klarman family|genspring|greenfield capital family)\\b");
    private static final Pattern FUND_OF_FUNDS = Pattern.compile(
        "\\b(fund of funds?|fof|pantheon|stepstone|harbourvest|hamilton lane|partners group|lexington capital partners)\\b");
    private static final Pattern SOVEREIGN = Pattern.compile(
        "\\b(sovereign|investment authority|inv authority|monetary authority|public investment fund|future fund|norges bank|mubadala|temasek|gic|korea investment corporation|bank of korea|abu dhabi investment council)\\b");
    private static final Pattern INSURANCE = Pattern.compile(
        "\\b(insurance|assurance|reinsurance|life insurance|life assurance|fire marine|mutual life|metlife|prudential|sumitomo life|axa|aviva|sun life|aflac)\\b");
    private static final Pattern ENDOWMENT = Pattern.compile(
        "\\b(endowment|university|college|school endowment)\\b");
    private static final Pattern FOUNDATION = Pattern.compile("\\bfoundation\\b");
    private static final Pattern HEALTHCARE = Pattern.compile(
        "\\b(healthcare|health|hospital|medical center|commonspirit|mayo|kaiser)\\b");
    private static final Pattern PUBLIC_PENSION = Pattern.compile(
        "\\b(retirement system|ret sys|retirement board|public employees|municipal employees|teachers|police|state retirement|city employees|government employees)\\b");
    private static final Pattern PENSION = Pattern.compile(
        "\\b(pension|pension plan|provident fund|superannuation|super|versorgung|pensioenfonds|pensionsforsakring|afp|afore)\\b");
    private static final Pattern INVESTMENT_CONSULTANT = Pattern.compile(
        "\\b(investment consultant|consulting|callan|mercer|nepc|meketa|wilshire|cambridge associates)\\b");
    private static final Pattern HEDGE_FUND = Pattern.compile(
        "\\b(hedge fund|bridgewater|citadel|millennium|two sigma|tiger global|renaissance)\\b");
    private static final Pattern CORPORATE = Pattern.compile(
        "\\b(corporation|corp|company|co\\b|inc\\b|ltd\\b)\\b");
    private static final Pattern OTHER_INSTITUTIONAL = Pattern.compile(
        "\\b(capital partners|investment partners|asset management|investment management|inv mgmt|ventures|partners|investment group|inv group)\\b");

    public static String derive(String investorName) {
        String name = normalize(investorName);
        if (name.isBlank()) return "";

        if (matches(FAMILY_OFFICE, name)) return "Family Office";
        if (matches(FUND_OF_FUNDS, name)) return "Fund of Funds";
        if (matches(SOVEREIGN, name)) return "Sovereign Wealth Fund";
        if (matches(ENDOWMENT, name)) return "Endowment";
        if (matches(FOUNDATION, name)) return "Foundation";
        if (matches(PUBLIC_PENSION, name)) return "Public Pension";
        if (matches(PENSION, name)) return "Pension Fund";
        if (matches(INSURANCE, name)) return "Insurance Company";
        if (matches(HEALTHCARE, name)) return "Healthcare";
        if (matches(INVESTMENT_CONSULTANT, name)) return "Investment Consultant";
        if (matches(HEDGE_FUND, name)) return "Hedge Fund";
        if (matches(CORPORATE, name)) return "Corporate";
        if (matches(OTHER_INSTITUTIONAL, name)) return "Other Institutional";
        return "";
    }

    private static boolean matches(Pattern pattern, String value) {
        return pattern.matcher(value).find();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw
            .toLowerCase(Locale.ROOT)
            .replace("&", " ")
            .replace(".", "")
            .replace("'", "")
            .replaceAll("[^a-z0-9]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
