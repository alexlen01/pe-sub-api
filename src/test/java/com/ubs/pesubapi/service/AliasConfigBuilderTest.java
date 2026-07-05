package com.ubs.pesubapi.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.entity.FmAlias;
import com.ubs.pesubapi.entity.FmCanonicalField;
import com.ubs.pesubapi.repository.FmAliasRepository;
import com.ubs.pesubapi.repository.FmCanonicalFieldRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliasConfigBuilderTest {

    private final FmCanonicalFieldRepository canonicalRepo = mock(FmCanonicalFieldRepository.class);
    private final FmAliasRepository aliasRepo = mock(FmAliasRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AliasConfigBuilder builder = new AliasConfigBuilder(canonicalRepo, aliasRepo, mapper);

    @Test
    void buildJson_includesOnlyMatchingBankAliasesPlusGlobalAliases() throws Exception {
        FmCanonicalField uncalled = canonical(1, "Uncalled Capital", "UNCALLED");
        FmCanonicalField nav = canonical(2, "NAV", null);

        when(canonicalRepo.findAllByOrderByGroupSortAscFieldSortAsc()).thenReturn(List.of(uncalled, nav));
        when(aliasRepo.findAllByOrderByCanonicalFieldIdAscAliasSortAsc()).thenReturn(List.of(
            alias(1, 1, "Uncalled Capital", "Core", null),
            alias(1, 2, "Unfunded Cap", "Bank", "BNY"),
            alias(1, 3, "Outstanding Callable", "Bank", "SVB"),
            alias(2, 1, "NAV", "Core", null),
            alias(2, 2, "Goldman NAV", "Bank", "GS")
        ));

        Map<String, List<String>> bny = mapper.readValue(
            builder.buildJson("BNY Mellon"), new TypeReference<>() {});

        assertThat(bny.get("UNCALLED")).contains("Uncalled Capital", "Unfunded Cap");
        assertThat(bny.get("UNCALLED")).doesNotContain("Outstanding Callable");
        assertThat(bny.get("NAV")).contains("NAV");
        assertThat(bny.get("NAV")).doesNotContain("Goldman NAV");

        Map<String, List<String>> gs = mapper.readValue(
            builder.buildJson("Goldman Sachs Bank USA"), new TypeReference<>() {});

        assertThat(gs.get("NAV")).contains("Goldman NAV");
    }

    private FmCanonicalField canonical(int id, String canonical, String extractionKey) {
        FmCanonicalField field = new FmCanonicalField();
        ReflectionTestUtils.setField(field, "id", id);
        field.setCanonical(canonical);
        field.setGroupName("Test");
        field.setGroupSort(1);
        field.setFieldSort(id);
        field.setLpMasterField("Test - " + canonical);
        field.setExtractionKey(extractionKey);
        return field;
    }

    private FmAlias alias(int canonicalFieldId, int aliasSort, String aliasText, String tier, String bank) {
        FmAlias alias = new FmAlias();
        alias.setCanonicalFieldId(canonicalFieldId);
        alias.setAliasSort(aliasSort);
        alias.setAliasText(aliasText);
        alias.setTier(tier);
        alias.setBank(bank);
        return alias;
    }
}
