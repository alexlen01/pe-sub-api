package com.ubs.pesubapi.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvestorTypeDeriverTest {

    @Test
    void derive_identifiesCommonAgentBbInvestorTypes() {
        assertThat(InvestorTypeDeriver.derive("Arkansas Teachers' Retirement System")).isEqualTo("Public Pension");
        assertThat(InvestorTypeDeriver.derive("ExxonMobil Corporation Pension Plan")).isEqualTo("Pension Fund");
        assertThat(InvestorTypeDeriver.derive("Healthcare of Ontario Pension Plan Trust Fund")).isEqualTo("Pension Fund");
        assertThat(InvestorTypeDeriver.derive("Veritas Pension Insurance")).isEqualTo("Pension Fund");
        assertThat(InvestorTypeDeriver.derive("Sumitomo Life Insurance Company")).isEqualTo("Insurance Company");
        assertThat(InvestorTypeDeriver.derive("University of Chicago Endowment Fund")).isEqualTo("Endowment");
        assertThat(InvestorTypeDeriver.derive("David & Lucile Packard Foundation")).isEqualTo("Foundation");
        assertThat(InvestorTypeDeriver.derive("Abu Dhabi Inv. Authority")).isEqualTo("Sovereign Wealth Fund");
        assertThat(InvestorTypeDeriver.derive("Pantheon Ventures (UK) LLP")).isEqualTo("Fund of Funds");
        assertThat(InvestorTypeDeriver.derive("Greenfield Capital Family Office")).isEqualTo("Family Office");
        assertThat(InvestorTypeDeriver.derive("Cambridge Associates LLC")).isEqualTo("Investment Consultant");
        assertThat(InvestorTypeDeriver.derive("Two Sigma Investments")).isEqualTo("Hedge Fund");
    }

    @Test
    void derive_returnsBlankForUnknownNames() {
        assertThat(InvestorTypeDeriver.derive("Q West Holding LLC")).isBlank();
        assertThat(InvestorTypeDeriver.derive(null)).isBlank();
    }
}
