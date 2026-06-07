package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.entity.FmAlias;
import com.ubs.pesubapi.entity.FmCanonicalField;
import com.ubs.pesubapi.repository.FmAliasRepository;
import com.ubs.pesubapi.repository.FmCanonicalFieldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the alias configuration that pe-sub-api passes to pe-sub-extraction on every
 * extract call.  The result is a JSON string keyed by CanonicalField enum name so that
 * pe-sub-extraction's HeaderMatcher uses live, user-configurable DB aliases instead of
 * its hardcoded fallback map.
 *
 * Only fm_canonical_fields rows with a non-null extraction_key are included — those are
 * the fields the extraction service actually knows how to parse into typed record values.
 */
@Service
public class AliasConfigBuilder {

    private static final Logger log = LoggerFactory.getLogger(AliasConfigBuilder.class);

    private final FmCanonicalFieldRepository canonicalRepo;
    private final FmAliasRepository          aliasRepo;
    private final ObjectMapper               mapper;

    public AliasConfigBuilder(FmCanonicalFieldRepository canonicalRepo,
                              FmAliasRepository aliasRepo,
                              ObjectMapper mapper) {
        this.canonicalRepo = canonicalRepo;
        this.aliasRepo     = aliasRepo;
        this.mapper        = mapper;
    }

    /**
     * Returns a JSON string of shape {@code { "INVESTOR_NAME": ["alias1", ...], ... }}
     * or null if the DB is empty / serialisation fails (caller falls back to hardcoded).
     */
    public String buildJson() {
        List<FmCanonicalField> allFields =
            canonicalRepo.findAllByOrderByGroupSortAscFieldSortAsc();
        if (allFields.isEmpty()) return null;

        // Extractable fields use extraction_key as the map key; others use canonical value
        Map<Integer, String> keyById = allFields.stream()
            .collect(Collectors.toMap(
                FmCanonicalField::getId,
                f -> f.getExtractionKey() != null ? f.getExtractionKey() : f.getCanonical()
            ));

        List<FmAlias> allAliases = aliasRepo.findAllByOrderByCanonicalFieldIdAscAliasSortAsc();

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (FmAlias alias : allAliases) {
            String key = keyById.get(alias.getCanonicalFieldId());
            if (key == null) continue;
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(alias.getAliasText());
        }

        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise alias config — extraction will use hardcoded fallback: {}", e.getMessage());
            return null;
        }
    }
}
